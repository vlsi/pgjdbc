/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Provider;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.SqlCommand;
import org.postgresql.core.Tuple;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class PgStatement implements Statement, BaseStatement {
  private static final String[] NO_RETURNING_COLUMNS = new String[0];

  /**
   * Default state for use or not binary transfers. Can use only for testing purposes
   */
  private static final boolean DEFAULT_FORCE_BINARY_TRANSFERS =
      Boolean.getBoolean("org.postgresql.forceBinary");
  // only for testing purposes. even single shot statements will use binary transfers
  private boolean forceBinaryTransfers = DEFAULT_FORCE_BINARY_TRANSFERS;

  protected final ResourceLock lock = new ResourceLock();
  protected @Nullable ArrayList<Query> batchStatements;
  protected @Nullable ArrayList<@Nullable ParameterList> batchParameters;
  protected final int resultsettype; // the resultset type to return (ResultSet.TYPE_xxx)
  protected final int concurrency; // is it updateable or not? (ResultSet.CONCUR_xxx)
  private final int rsHoldability;
  private boolean poolable;
  private boolean closeOnCompletion;
  protected int fetchdirection = ResultSet.FETCH_FORWARD;
  // fetch direction hint (currently ignored)

  /**
   * Protects current statement from cancelTask starting, waiting for a bit, and waking up exactly
   * on subsequent query execution. The idea is to atomically compare and swap the reference to the
   * task, so the task can detect that statement executes different query than the one the
   * cancelTask was created. Note: the field must be set/get/compareAndSet via
   * {@link #CANCEL_TIMER_UPDATER} as per {@link AtomicReferenceFieldUpdater} javadoc.
   */
  @SuppressWarnings("unused")
  private volatile @Nullable TimerTask cancelTimerTask;

  @SuppressWarnings("RedundantCast")
  // Cast is needed for checkerframework to accept the code
  private static final AtomicReferenceFieldUpdater<PgStatement, @Nullable TimerTask> CANCEL_TIMER_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(
          PgStatement.class, (Class<@Nullable TimerTask>) TimerTask.class, "cancelTimerTask");

  /**
   * Protects statement from out-of-order cancels. It protects from both
   * {@link #setQueryTimeout(int)} and {@link #cancel()} induced ones.
   *
   * {@link #execute(String)} and friends change the field to
   * {@link StatementCancelState#IN_QUERY} during execute. {@link #cancel()}
   * ignores cancel request if state is {@link StatementCancelState#IDLE}.
   * In case {@link #execute(String)} observes non-{@link StatementCancelState#IDLE} state as it
   * completes the query, it waits till {@link StatementCancelState#CANCELLED}. Note: the field must be
   * set/get/compareAndSet via {@link #STATE_UPDATER} as per {@link AtomicIntegerFieldUpdater}
   * javadoc.
   */
  private volatile StatementCancelState statementState = StatementCancelState.IDLE;

  private static final AtomicReferenceFieldUpdater<PgStatement, StatementCancelState> STATE_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(PgStatement.class, StatementCancelState.class, "statementState");

  /**
   * Does the caller of execute/executeUpdate want generated keys for this execution? This is set by
   * Statement methods that have generated keys arguments and cleared after execution is complete.
   */
  protected boolean wantsGeneratedKeysOnce;

  /**
   * Was this PreparedStatement created to return generated keys for every execution? This is set at
   * creation time and never cleared by execution.
   */
  public boolean wantsGeneratedKeysAlways;

  // The connection who created us
  protected final PgConnection connection;

  /**
   * The warnings chain.
   */
  protected volatile @Nullable PSQLWarningWrapper warnings;

  /**
   * Maximum number of rows to return, 0 = unlimited.
   */
  protected int maxrows;

  /**
   * Number of rows to get in a batch.
   */
  protected int fetchSize;

  /**
   * Timeout (in milliseconds) for a query.
   */
  protected long timeout;

  protected boolean replaceProcessingEnabled = true;

  /**
   * The current results.
   */
  protected @Nullable ResultWrapper result;

  /**
   * The first unclosed result.
   */
  protected @Nullable @GuardedBy("<self>") ResultWrapper firstUnclosedResult;

  /**
   * Results returned by a statement that wants generated keys.
   */
  protected @Nullable ResultWrapper generatedKeys;

  protected int mPrepareThreshold; // Reuse threshold to enable use of PREPARE

  protected int maxFieldSize;

  protected boolean adaptiveFetch;

  private @Nullable TimestampUtils timestampUtils; // our own Object because it's not thread safe

  @SuppressWarnings("method.invocation")
  PgStatement(PgConnection c, int rsType, int rsConcurrency, int rsHoldability)
      throws SQLException {
    this.connection = c;
    forceBinaryTransfers |= c.getForceBinary();
    // validation check for allowed values of resultset type
    if (rsType != ResultSet.TYPE_FORWARD_ONLY && rsType != ResultSet.TYPE_SCROLL_INSENSITIVE && rsType != ResultSet.TYPE_SCROLL_SENSITIVE) {
      throw new PSQLException(GT.tr("Unknown value for ResultSet type"),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    resultsettype = rsType;
    // validation check for allowed values of resultset concurrency
    if (rsConcurrency != ResultSet.CONCUR_READ_ONLY && rsConcurrency != ResultSet.CONCUR_UPDATABLE) {
      throw new PSQLException(GT.tr("Unknown value for ResultSet concurrency"),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    concurrency = rsConcurrency;
    setFetchSize(c.getDefaultFetchSize());
    setPrepareThreshold(c.getPrepareThreshold());
    setAdaptiveFetch(c.getAdaptiveFetch());
    // validation check for allowed values of resultset holdability
    if (rsHoldability != ResultSet.HOLD_CURSORS_OVER_COMMIT && rsHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      throw new PSQLException(GT.tr("Unknown value for ResultSet holdability"),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    this.rsHoldability = rsHoldability;
  }

  @Override
  @SuppressWarnings("method.invocation")
  public ResultSet createResultSet(@Nullable Query originalQuery, Field[] fields, List<Tuple> tuples,
      @Nullable ResultCursor cursor) throws SQLException {
    PgResultSet newResult = new PgResultSet(originalQuery, this, fields, tuples, cursor,
        getMaxRows(), getMaxFieldSize(), getResultSetType(), getResultSetConcurrency(),
        getResultSetHoldability(), getAdaptiveFetch());
    newResult.setFetchSize(getFetchSize());
    newResult.setFetchDirection(getFetchDirection());
    return newResult;
  }

  public BaseConnection getPGConnection() {
    return connection;
  }

  public @Nullable String getFetchingCursorName() {
    return null;
  }

  @Override
  public @NonNegative int getFetchSize() {
    return fetchSize;
  }

  protected boolean wantsScrollableResultSet() {
    return resultsettype != ResultSet.TYPE_FORWARD_ONLY;
  }

  protected boolean wantsHoldableResultSet() {
    // FIXME: false if not supported
    return rsHoldability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  /**
   * ResultHandler implementations for updates, queries, and either-or.
   */
  public class StatementResultHandler extends ResultHandlerBase {
    private @Nullable ResultWrapper results;
    private @Nullable ResultWrapper lastResult;

    @Nullable ResultWrapper getResults() {
      return results;
    }

    private void append(ResultWrapper newResult) {
      if (results == null) {
        lastResult = results = newResult;
      } else {
        castNonNull(lastResult).append(newResult);
      }
    }

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        @Nullable ResultCursor cursor) {
      try {
        ResultSet rs = PgStatement.this.createResultSet(fromQuery, fields, tuples, cursor);
        append(new ResultWrapper(rs));
      } catch (SQLException e) {
        handleError(e);
      }
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
      append(new ResultWrapper(updateCount, insertOID));
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      PgStatement.this.addWarning(warning);
    }

  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!executeWithFlags(sql, 0)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      return getSingleResultSet();
    }
  }

  protected ResultSet getSingleResultSet() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      ResultWrapper result = castNonNull(this.result);
      if (result.getNext() != null) {
        throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."),
            PSQLState.TOO_MANY_RESULTS);
      }

      return castNonNull(result.getResultSet(), "result.getResultSet()");
    }
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      executeWithFlags(sql, QueryExecutor.QUERY_NO_RESULTS);
      checkNoResultUpdate();
      return getUpdateCount();
    }
  }

  protected final void checkNoResultUpdate() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      ResultWrapper iter = result;
      while (iter != null) {
        if (iter.getResultSet() != null) {
          throw new PSQLException(GT.tr("A result was returned when none was expected."),
              PSQLState.TOO_MANY_RESULTS);
        }
        iter = iter.getNext();
      }
    }
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return executeWithFlags(sql, 0);
  }

  @Override
  public boolean executeWithFlags(String sql, int flags) throws SQLException {
    return executeCachedSql(sql, flags, NO_RETURNING_COLUMNS);
  }

  private boolean executeCachedSql(String sql, int flags,
      String @Nullable [] columnNames) throws SQLException {
    PreferQueryMode preferQueryMode = connection.getPreferQueryMode();
    // Simple statements should not replace ?, ? with $1, $2
    boolean shouldUseParameterized = false;
    QueryExecutor queryExecutor = connection.getQueryExecutor();
    Object key = queryExecutor
        .createQueryKey(sql, replaceProcessingEnabled, shouldUseParameterized, columnNames);
    CachedQuery cachedQuery;
    boolean shouldCache = preferQueryMode == PreferQueryMode.EXTENDED_CACHE_EVERYTHING;
    if (shouldCache) {
      cachedQuery = queryExecutor.borrowQueryByKey(key);
    } else {
      cachedQuery = queryExecutor.createQueryByKey(key);
    }
    if (wantsGeneratedKeysOnce) {
      SqlCommand sqlCommand = cachedQuery.query.getSqlCommand();
      wantsGeneratedKeysOnce = sqlCommand != null && sqlCommand.isReturningKeywordPresent();
    }
    boolean res;
    try {
      res = executeWithFlags(cachedQuery, flags);
    } finally {
      if (shouldCache) {
        queryExecutor.releaseQuery(cachedQuery);
      }
    }
    return res;
  }

  @Override
  public boolean executeWithFlags(CachedQuery simpleQuery, int flags) throws SQLException {
    checkClosed();
    if (connection.getPreferQueryMode().compareTo(PreferQueryMode.EXTENDED) < 0) {
      flags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
    }
    execute(simpleQuery, null, flags);
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      return result != null && result.getResultSet() != null;
    }
  }

  @Override
  public boolean executeWithFlags(int flags) throws SQLException {
    checkClosed();
    throw new PSQLException(GT.tr("Can''t use executeWithFlags(int) on a Statement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  /*
  If there are multiple result sets we close any that have been processed and left open
  by the client.
   */
  private void closeUnclosedProcessedResults() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      ResultWrapper resultWrapper = this.firstUnclosedResult;
      ResultWrapper currentResult = this.result;
      for (; resultWrapper != currentResult && resultWrapper != null;
           resultWrapper = resultWrapper.getNext()) {
        PgResultSet rs = (PgResultSet) resultWrapper.getResultSet();
        if (rs != null) {
          rs.closeInternally();
        }
      }
      firstUnclosedResult = resultWrapper;
    }
  }

  protected void closeForNextExecution() throws SQLException {

    // Every statement execution clears any previous warnings.
    clearWarnings();

    // Close any existing resultsets associated with this statement.
    try (ResourceLock ignore = lock.obtain()) {
      closeUnclosedProcessedResults();

      if ( this.result != null && this.result.getResultSet() != null ) {
        this.result.getResultSet().close();
      }
      result = null;

      ResultWrapper generatedKeys = this.generatedKeys;
      if (generatedKeys != null) {
        ResultSet resultSet = generatedKeys.getResultSet();
        if (resultSet != null) {
          resultSet.close();
        }
        this.generatedKeys = null;
      }
    }
  }

  /**
   * Returns true if query is unlikely to be reused.
   *
   * @param cachedQuery to check (null if current query)
   * @return true if query is unlikely to be reused
   */
  protected boolean isOneShotQuery(@Nullable CachedQuery cachedQuery) {
    if (cachedQuery == null) {
      return true;
    }
    cachedQuery.increaseExecuteCount();
    return (mPrepareThreshold == 0 || cachedQuery.getExecuteCount() < mPrepareThreshold)
        && !getForceBinaryTransfer();
  }

  protected final void execute(CachedQuery cachedQuery,
      @Nullable ParameterList queryParameters, int flags)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      try {
        executeInternal(cachedQuery, queryParameters, flags);
      } catch (SQLException e) {
        // Don't retry composite queries as it might get partially executed
        if (cachedQuery.query.getSubqueries() != null
            || !connection.getQueryExecutor().willHealOnRetry(e)) {
          throw e;
        }
        cachedQuery.query.close();
        // Execute the query one more time
        executeInternal(cachedQuery, queryParameters, flags);
      }
    }
  }

  private void executeInternal(CachedQuery cachedQuery,
      @Nullable ParameterList queryParameters, int flags)
      throws SQLException {
    closeForNextExecution();

    // Enable cursor-based resultset if possible.
    if (fetchSize > 0 && !wantsScrollableResultSet() && !connection.getAutoCommit()
        && !wantsHoldableResultSet()) {
      flags |= QueryExecutor.QUERY_FORWARD_CURSOR;
    }

    if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways) {
      flags |= QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS;

      // If the no results flag is set (from executeUpdate)
      // clear it so we get the generated keys results.
      //
      if ((flags & QueryExecutor.QUERY_NO_RESULTS) != 0) {
        flags &= ~(QueryExecutor.QUERY_NO_RESULTS);
      }
    }

    // Only use named statements after we hit the threshold. Note that only
    // named statements can be transferred in binary format.
    // isOneShotQuery will check to see if we have hit the prepareThreshold count

    if (isOneShotQuery(cachedQuery)) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    }

    if (connection.getAutoCommit()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }
    if (connection.hintReadOnly()) {
      flags |= QueryExecutor.QUERY_READ_ONLY_HINT;
    }

    // updateable result sets do not yet support binary updates
    if (concurrency != ResultSet.CONCUR_READ_ONLY) {
      flags |= QueryExecutor.QUERY_NO_BINARY_TRANSFER;
    }

    Query queryToExecute = cachedQuery.query;

    if (queryToExecute.isEmpty()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }

    if (!queryToExecute.isStatementDescribed() && forceBinaryTransfers
        && (flags & QueryExecutor.QUERY_EXECUTE_AS_SIMPLE) == 0) {
      // Simple 'Q' execution does not need to know parameter types
      // When binaryTransfer is forced, then we need to know resulting parameter and column types,
      // thus sending a describe request.
      int flags2 = flags | QueryExecutor.QUERY_DESCRIBE_ONLY;
      StatementResultHandler handler2 = new StatementResultHandler();
      connection.getQueryExecutor().execute(queryToExecute, queryParameters, handler2, 0, 0,
          flags2);
      // We should not create temporary ResultSet when processing "describe row" command;
      // however, it is the way PgPreparedStatement#getMetaData() works now
      ResultWrapper result2 = handler2.getResults();
      if (result2 != null) {
        // Note: if the user requested "statement.closeOnCompletion()" then we should not
        // let the driver's internal resultset to close the user statement
        // At best we should stop creating the intermediate ResultSet objects
        boolean prevCloseOnCompletion = closeOnCompletion;
        closeOnCompletion = false;
        castNonNull(result2.getResultSet(), "result2.getResultSet()").close();
        closeOnCompletion = prevCloseOnCompletion;
      }
    }

    StatementResultHandler handler = new StatementResultHandler();
    try (ResourceLock ignore = lock.obtain()) {
      result = null;
    }
    try {
      startTimer();
      connection.getQueryExecutor().execute(queryToExecute, queryParameters, handler, maxrows,
          fetchSize, flags, adaptiveFetch);
    } finally {
      killTimerTask();
    }
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();

      ResultWrapper currentResult = handler.getResults();
      result = firstUnclosedResult = currentResult;

      if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways) {
        generatedKeys = currentResult;
        result = castNonNull(currentResult, "handler.getResults()").getNext();

        if (wantsGeneratedKeysOnce) {
          wantsGeneratedKeysOnce = false;
        }
      }
    }
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    checkClosed();
    // No-op.
  }

  private volatile int isClosed;
  private static final AtomicIntegerFieldUpdater<PgStatement> IS_CLOSED_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(
          PgStatement.class, "isClosed");

  @Override
  public int getUpdateCount() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      if (result == null || result.getResultSet() != null) {
        return -1;
      }

      long count = result.getUpdateCount();
      return count > Integer.MAX_VALUE ? Statement.SUCCESS_NO_INFO : (int) count;
    }
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_ALL_RESULTS);
  }

  @Override
  public int getMaxRows() throws SQLException {
    checkClosed();
    return maxrows;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    checkClosed();
    if (max < 0) {
      throw new PSQLException(
          GT.tr("Maximum number of rows must be a value greater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    maxrows = max;
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    checkClosed();
    replaceProcessingEnabled = enable;
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    checkClosed();
    long seconds = timeout / 1000;
    if (seconds >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) seconds;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    setQueryTimeoutMs(seconds * 1000L);
  }

  /**
   * The queryTimeout limit is the number of milliseconds the driver will wait for a Statement to
   * execute. If the limit is exceeded, a SQLException is thrown.
   *
   * @return the current query timeout limit in milliseconds; 0 = unlimited
   * @throws SQLException if a database access error occurs
   */
  public long getQueryTimeoutMs() throws SQLException {
    checkClosed();
    return timeout;
  }

  /**
   * Sets the queryTimeout limit.
   *
   * @param millis - the new query timeout limit in milliseconds
   * @throws SQLException if a database access error occurs
   */
  public void setQueryTimeoutMs(long millis) throws SQLException {
    checkClosed();

    if (millis < 0) {
      throw new PSQLException(GT.tr("Query timeout must be a value greater than or equals to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    timeout = millis;
  }

  /**
   * Either initializes new warning wrapper, or adds warning onto the chain.
   *
   * <p>Although warnings are expected to be added sequentially, the warnings chain may be cleared
   * concurrently at any time via {@link #clearWarnings()}, therefore it is possible that a warning
   * added via this method is placed onto the end of the previous warning chain</p>
   *
   * @param warn warning to add
   */
  public void addWarning(SQLWarning warn) {
    //copy reference to avoid NPE from concurrent modification of this.warnings
    final PSQLWarningWrapper warnWrap = this.warnings;
    if (warnWrap == null) {
      this.warnings = new PSQLWarningWrapper(warn);
    } else {
      warnWrap.addWarning(warn);
    }
  }

  @Override
  public @Nullable SQLWarning getWarnings() throws SQLException {
    checkClosed();
    //copy reference to avoid NPE from concurrent modification of this.warnings
    final PSQLWarningWrapper warnWrap = this.warnings;
    return warnWrap != null ? warnWrap.getFirstWarning() : null;
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return maxFieldSize;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    checkClosed();
    if (max < 0) {
      throw new PSQLException(
          GT.tr("The maximum field size must be a value greater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    maxFieldSize = max;
  }

  /**
   * Clears the warning chain.
   *
   * <p>Note that while it is safe to clear warnings while the query is executing, warnings that are
   * added between calls to {@link #getWarnings()} and #clearWarnings() may be missed.
   * Therefore you should hold a reference to the tail of the previous warning chain
   * and verify if its {@link SQLWarning#getNextWarning()} value is holds any new value.</p>
   */
  @Override
  public void clearWarnings() throws SQLException {
    warnings = null;
  }

  @Override
  public @Nullable ResultSet getResultSet() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();

      if (result == null) {
        return null;
      }

      return result.getResultSet();
    }
  }

  /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   *
   * {@inheritDoc}
   */
  @Override
  public final void close() throws SQLException {
    // closing an already closed Statement is a no-op.
    if (!IS_CLOSED_UPDATER.compareAndSet(this, 0, 1)) {
      return;
    }

    cancel();

    closeForNextExecution();

    closeImpl();
  }

  /**
   * This is guaranteed to be called exactly once even in case of concurrent {@link #close()} calls.
   * @throws SQLException in case of error
   */
  protected void closeImpl() throws SQLException {
  }

  /*
   *
   * The following methods are postgres extensions and are defined in the interface BaseStatement
   *
   */

  @Override
  public long getLastOID() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      if (result == null) {
        return 0;
      }
      return result.getInsertOID();
    }
  }

  @Override
  public void setPrepareThreshold(int newThreshold) throws SQLException {
    checkClosed();

    if (newThreshold < 0) {
      forceBinaryTransfers = true;
      newThreshold = 1;
    }

    this.mPrepareThreshold = newThreshold;
  }

  @Override
  public int getPrepareThreshold() {
    return mPrepareThreshold;
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setUseServerPrepare(boolean flag) throws SQLException {
    setPrepareThreshold(flag ? 1 : 0);
  }

  @Override
  public boolean isUseServerPrepare() {
    return false;
  }

  protected void checkClosed() throws SQLException {
    if (isClosed()) {
      throw new PSQLException(GT.tr("This statement has been closed."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
  }

  // ** JDBC 2 Extensions **

  @Override
  public void addBatch(String sql) throws SQLException {
    checkClosed();

    ArrayList<Query> batchStatements = this.batchStatements;
    if (batchStatements == null) {
      this.batchStatements = batchStatements = new ArrayList<>();
    }
    ArrayList<@Nullable ParameterList> batchParameters = this.batchParameters;
    if (batchParameters == null) {
      this.batchParameters = batchParameters = new ArrayList<@Nullable ParameterList>();
    }

    // Simple statements should not replace ?, ? with $1, $2
    boolean shouldUseParameterized = false;
    CachedQuery cachedQuery = connection.createQuery(sql, replaceProcessingEnabled, shouldUseParameterized);
    batchStatements.add(cachedQuery.query);
    batchParameters.add(null);
  }

  @Override
  public void clearBatch() throws SQLException {
    if (batchStatements != null) {
      batchStatements.clear();
    }
    if (batchParameters != null) {
      batchParameters.clear();
    }
  }

  protected BatchResultHandler createBatchHandler(Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    return new BatchResultHandler(this, queries, parameterLists,
        wantsGeneratedKeysAlways);
  }

  @RequiresNonNull({"batchStatements", "batchParameters"})
  private BatchResultHandler internalExecuteBatch() throws SQLException {
    // Construct query/parameter arrays.
    transformQueriesAndParameters();
    ArrayList<Query> batchStatements = castNonNull(this.batchStatements);
    ArrayList<@Nullable ParameterList> batchParameters = castNonNull(this.batchParameters);
    // Empty arrays should be passed to toArray
    // see http://shipilev.net/blog/2016/arrays-wisdom-ancients/
    Query[] queries = batchStatements.toArray(new Query[0]);
    @Nullable ParameterList[] parameterLists = batchParameters.toArray(new ParameterList[0]);
    batchStatements.clear();
    batchParameters.clear();

    int flags;

    if (wantsGeneratedKeysAlways) {
      /*
       * This batch will return generated keys, tell the executor to expect result rows. We also
       * force a Describe later so we know the size of the results to expect.
       *
       * If the parameter type(s) change between batch entries and the default binary-mode changes
       * we might get mixed binary and text in a single result set column, which we cannot handle.
       * To prevent this, disable binary transfer mode in batches that return generated keys. See
       * GitHub issue #267
       */
      flags = QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS | QueryExecutor.QUERY_NO_BINARY_TRANSFER;
    } else {
      // If a batch hasn't specified that it wants generated keys, using the appropriate
      // Connection.createStatement(...) interfaces, disallow any result set.
      flags = QueryExecutor.QUERY_NO_RESULTS;
    }

    PreferQueryMode preferQueryMode = connection.getPreferQueryMode();
    if (preferQueryMode == PreferQueryMode.SIMPLE
        || (preferQueryMode == PreferQueryMode.EXTENDED_FOR_PREPARED
        && parameterLists[0] == null)) {
      flags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
    }

    if (isOneShotQuery(null)) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    } else {
      /*
       * It's also necessary to force a Describe on the first execution of the new statement, even
       * though we already described it, to work around bug #267.
       */
      flags |= QueryExecutor.QUERY_FORCE_DESCRIBE_PORTAL;
    }

    if (connection.getAutoCommit()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }
    if (connection.hintReadOnly()) {
      flags |= QueryExecutor.QUERY_READ_ONLY_HINT;
    }

    BatchResultHandler handler;
    handler = createBatchHandler(queries, parameterLists);

    try (ResourceLock ignore = lock.obtain()) {
      result = null;
    }

    try {
      startTimer();
      connection.getQueryExecutor().execute(queries, parameterLists, handler, maxrows, fetchSize,
          flags, adaptiveFetch);
    } finally {
      killTimerTask();
      // There might be some rows generated even in case of failures
      try (ResourceLock ignore = lock.obtain()) {
        checkClosed();
        if (wantsGeneratedKeysAlways) {
          generatedKeys = new ResultWrapper(handler.getGeneratedKeys());
        }
      }
    }
    return handler;
  }

  @Override
  public int[] executeBatch() throws SQLException {
    checkClosed();
    closeForNextExecution();

    if (batchStatements == null || batchStatements.isEmpty() || batchParameters == null) {
      return new int[0];
    }

    return internalExecuteBatch().getUpdateCount();
  }

  @Override
  public void cancel() throws SQLException {
    if (statementState == StatementCancelState.IDLE) {
      return;
    }
    if (!STATE_UPDATER.compareAndSet(this, StatementCancelState.IN_QUERY,
        StatementCancelState.CANCELING)) {
      // Not in query, there's nothing to cancel
      return;
    }
    // Use connection lock to avoid spinning in killTimerTask
    try (ResourceLock connectionLock = connection.obtainLock()) {
      try {
        connection.cancelQuery();
      } finally {
        STATE_UPDATER.set(this, StatementCancelState.CANCELLED);
        connection.lockCondition().signalAll(); // wake-up killTimerTask
      }
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    return connection;
  }

  @Override
  public int getFetchDirection() {
    return fetchdirection;
  }

  @Override
  public int getResultSetConcurrency() {
    return concurrency;
  }

  @Override
  public int getResultSetType() {
    return resultsettype;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    switch (direction) {
      case ResultSet.FETCH_FORWARD:
      case ResultSet.FETCH_REVERSE:
      case ResultSet.FETCH_UNKNOWN:
        fetchdirection = direction;
        break;
      default:
        throw new PSQLException(GT.tr("Invalid fetch direction constant: {0}.", direction),
            PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  @Override
  public void setFetchSize(@NonNegative int rows) throws SQLException {
    checkClosed();
    if (rows < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    fetchSize = rows;
  }

  private void startTimer() {
    /*
     * there shouldn't be any previous timer active, but better safe than sorry.
     */
    cleanupTimer();

    STATE_UPDATER.set(this, StatementCancelState.IN_QUERY);

    if (timeout == 0) {
      return;
    }

    TimerTask cancelTask = new StatementCancelTimerTask(this);

    CANCEL_TIMER_UPDATER.set(this, cancelTask);
    connection.addTimerTask(cancelTask, timeout);
  }

  void cancelIfStillNeeded(TimerTask timerTask) {
    try {
      if (!CANCEL_TIMER_UPDATER.compareAndSet(this, timerTask, null)) {
        // Nothing to do here, statement has already finished and cleared
        // cancelTimerTask reference
        return;
      }
      cancel();
    } catch (SQLException ignored) {
      // We can't do much if the cancel fails
    }
  }

  /**
   * Clears {@link #cancelTimerTask} if any. Returns true if and only if "cancel" timer task would
   * never invoke {@link #cancel()}.
   */
  private boolean cleanupTimer() {
    TimerTask timerTask = CANCEL_TIMER_UPDATER.get(this);
    if (timerTask == null) {
      // If timeout is zero, then timer task did not exist, so we safely report "all clear"
      return timeout == 0;
    }
    if (!CANCEL_TIMER_UPDATER.compareAndSet(this, timerTask, null)) {
      // Failed to update reference -> timer has just fired, so we must wait for the query state to
      // become "cancelling".
      return false;
    }
    timerTask.cancel();
    connection.purgeTimerTasks();
    // All clear
    return true;
  }

  private void killTimerTask() {
    boolean timerTaskIsClear = cleanupTimer();
    // The order is important here: in case we need to wait for the cancel task, the state must be
    // kept StatementCancelState.IN_QUERY, so cancelTask would be able to cancel the query.
    // It is believed that this case is very rare, so "additional cancel and wait below" would not
    // harm it.
    if (timerTaskIsClear && STATE_UPDATER.compareAndSet(this, StatementCancelState.IN_QUERY, StatementCancelState.IDLE)) {
      return;
    }

    // Being here means someone managed to call .cancel() and our connection did not receive
    // "timeout error"
    // We wait till state becomes "cancelled"
    boolean interrupted = false;
    try (ResourceLock connectionLock = connection.obtainLock()) {
      // state check is performed with connection lock so it detects "cancelled" state faster
      // In other words, it prevents unnecessary ".wait()" call
      while (!STATE_UPDATER.compareAndSet(this, StatementCancelState.CANCELLED, StatementCancelState.IDLE)) {
        try {
          // Note: wait timeout here is irrelevant since connection.obtainLock() would block until
          // .cancel finishes
          connection.lockCondition().await(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { // NOSONAR
          // Either re-interrupt this method or rethrow the "InterruptedException"
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  protected boolean getForceBinaryTransfer() {
    return forceBinaryTransfers;
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      if (result == null || result.getResultSet() != null) {
        return -1;
      }

      return result.getUpdateCount();
    }
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setLargeMaxRows");
  }

  @Override
  public long getLargeMaxRows() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLargeMaxRows");
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    checkClosed();
    closeForNextExecution();

    if (batchStatements == null || batchStatements.isEmpty() || batchParameters == null) {
      return new long[0];
    }

    return internalExecuteBatch().getLargeUpdateCount();
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      executeWithFlags(sql, QueryExecutor.QUERY_NO_RESULTS);
      checkNoResultUpdate();
      return getLargeUpdateCount();
    }
  }

  @Override
  public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
      return executeLargeUpdate(sql);
    }
    if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
      return executeLargeUpdate(sql, (String[]) null);
    }
    throw new PSQLException(GT.tr("Invalid value for autoGeneratedKeys {0}", autoGeneratedKeys),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
    if (columnIndexes == null || columnIndexes.length == 0) {
      return executeLargeUpdate(sql);
    }

    throw new PSQLException(GT.tr("Returning autogenerated keys by column index is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public long executeLargeUpdate(String sql, String @Nullable [] columnNames) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (columnNames != null && columnNames.length == 0) {
        return executeLargeUpdate(sql);
      }

      wantsGeneratedKeysOnce = true;
      if (!executeCachedSql(sql, 0, columnNames)) {
        // no resultset returned. What's a pity!
      }
      return getLargeUpdateCount();
    }
  }

  @Override
  public boolean isClosed() throws SQLException {
    return isClosed == 1;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    checkClosed();
    this.poolable = poolable;
  }

  @Override
  public boolean isPoolable() throws SQLException {
    checkClosed();
    return poolable;
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return closeOnCompletion;
  }

  protected void checkCompletion() throws SQLException {
    if (!closeOnCompletion) {
      return;
    }

    try (ResourceLock ignore = lock.obtain()) {
      ResultWrapper result = firstUnclosedResult;
      while (result != null) {
        ResultSet resultSet = result.getResultSet();
        if (resultSet != null && !resultSet.isClosed()) {
          return;
        }
        result = result.getNext();
      }
    }

    // prevent all ResultSet.close arising from Statement.close to loop here
    closeOnCompletion = false;
    try {
      close();
    } finally {
      // restore the status if one rely on isCloseOnCompletion
      closeOnCompletion = true;
    }
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      // CLOSE_CURRENT_RESULT
      if (current == Statement.CLOSE_CURRENT_RESULT && result != null
          && result.getResultSet() != null) {
        result.getResultSet().close();
      }

      // Advance resultset.
      if (result != null) {
        result = result.getNext();
      }

      // CLOSE_ALL_RESULTS
      if (current == Statement.CLOSE_ALL_RESULTS) {
        // Close preceding resultsets.
        closeUnclosedProcessedResults();
      }

      // Done.
      return result != null && result.getResultSet() != null;
    }
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkClosed();
      if (generatedKeys == null || generatedKeys.getResultSet() == null) {
        return createDriverResultSet(new Field[0], new ArrayList<>());
      }

      return generatedKeys.getResultSet();
    }
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
      return executeUpdate(sql);
    }
    if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
      return executeUpdate(sql, (String[]) null);
    }
    throw new PSQLException(GT.tr("Invalid value for autoGeneratedKeys {0}", autoGeneratedKeys),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    if (columnIndexes == null || columnIndexes.length == 0) {
      return executeUpdate(sql);
    }

    throw new PSQLException(GT.tr("Returning autogenerated keys by column index is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public int executeUpdate(String sql, String @Nullable [] columnNames) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (columnNames != null && columnNames.length == 0) {
        return executeUpdate(sql);
      }

      wantsGeneratedKeysOnce = true;
      if (!executeCachedSql(sql, 0, columnNames)) {
        // no resultset returned. What's a pity!
      }
      return getUpdateCount();
    }
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
      return execute(sql);
    }
    if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
      return execute(sql, (String[]) null);
    }
    throw new PSQLException(GT.tr("Invalid value for autoGeneratedKeys {0}", autoGeneratedKeys),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  @Override
  public boolean execute(String sql, int @Nullable [] columnIndexes) throws SQLException {
    if (columnIndexes != null && columnIndexes.length == 0) {
      return execute(sql);
    }

    throw new PSQLException(GT.tr("Returning autogenerated keys by column index is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public boolean execute(String sql, String @Nullable [] columnNames) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (columnNames != null && columnNames.length == 0) {
        return execute(sql);
      }

      wantsGeneratedKeysOnce = true;
      return executeCachedSql(sql, 0, columnNames);
    }
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return rsHoldability;
  }

  @Override
  public ResultSet createDriverResultSet(Field[] fields, List<Tuple> tuples)
      throws SQLException {
    return createResultSet(null, fields, tuples, null);
  }

  protected void transformQueriesAndParameters() throws SQLException {
  }

  @Override
  public void setAdaptiveFetch(boolean adaptiveFetch) {
    this.adaptiveFetch = adaptiveFetch;
  }

  @Override
  public boolean getAdaptiveFetch() {
    return adaptiveFetch;
  }

  protected TimestampUtils getTimestampUtils() {
    if (timestampUtils == null) {
      timestampUtils = new TimestampUtils(!connection.getQueryExecutor().getIntegerDateTimes(), (Provider<TimeZone>) new QueryExecutorTimeZoneProvider(connection.getQueryExecutor()));
    }
    return timestampUtils;
  }
}
