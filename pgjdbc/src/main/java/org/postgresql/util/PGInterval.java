/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.StringTokenizer;

/**
 * This implements a class that handles the PostgreSQL interval type.
 */
public class PGInterval extends PGobject implements Serializable, Cloneable {

  private static final int MICROS_IN_SECOND = 1000000;

  /** The fields a verbose literal can name, one bit each, so a repeat is a set bit. */
  private static final int YEARS = 1;
  private static final int MONTHS = 1 << 1;
  private static final int DAYS = 1 << 2;
  private static final int HOURS = 1 << 3;
  private static final int MINUTES = 1 << 4;
  private static final int SECONDS = 1 << 5;
  /** The fields one packed {@code hh:mm:ss} token fills. */
  private static final int TIME_FIELDS = HOURS | MINUTES | SECONDS;

  private int years;
  private int months;
  private int days;
  private int hours;
  private int minutes;
  private int wholeSeconds;
  private int microSeconds;
  private boolean isNull;

  /**
   * required by the driver.
   */
  public PGInterval() {
    type = "interval";
  }

  /**
   * Initialize a interval with a given interval string representation.
   *
   * @param value String represented interval (e.g. '3 years 2 mons')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see PGobject#setValue(String)
   */
  @SuppressWarnings("method.invocation")
  public PGInterval(String value) throws SQLException {
    this();
    setValue(value);
  }

  /**
   * Parses the {@code IntervalStyle=iso_8601} form, {@code P[nY][nM][nD][T[nH][nM][nS]]}. A field's
   * number may carry a sign and the seconds a fraction; {@code M} means months before the {@code T}
   * and minutes after it.
   *
   * <p>Every character has to belong to a field, so a stray designator is refused rather than dropped:
   * reading {@code P1X} as a zero interval would produce a value the server, which rejects the literal,
   * can never have sent. The {@code T} only switches {@code M} from months to minutes, so it may appear
   * anywhere and repeat ({@code P1DT} is one day, {@code PT} the zero interval); only a bare {@code P},
   * with neither a field nor a {@code T}, is refused.</p>
   *
   * <p>A field may repeat, and the repeats add up the way {@code interval_in} adds them: {@code P1Y2Y}
   * is three years and {@code P1Y-2Y} is minus one year. Fields absent from the literal are zero, so
   * parsing overwrites whatever this instance held before.</p>
   *
   * @param value the literal, known to start with {@code P}
   * @throws SQLException if the literal is not a well-formed ISO-8601 duration
   * @throws NumberFormatException if a field's number is not a number, caught by the caller
   * @throws ArithmeticException if repeated fields sum out of {@code int} range, caught by the caller
   */
  private void parseISO8601Format(String value) throws SQLException {
    boolean inTime = false;
    boolean anyField = false;
    boolean sawTimeSeparator = false;

    int years = 0;
    int months = 0;
    int days = 0;
    int hours = 0;
    int minutes = 0;
    double seconds = 0;

    int pos = 1; // Skip over the P
    while (pos < value.length()) {
      if (value.charAt(pos) == 'T') {
        // A T only switches M from months to minutes, so the server takes it anywhere and any number
        // of times: "PT1HT2M" is 01:02:00 and a trailing "P1DT" is simply one day.
        inTime = true;
        sawTimeSeparator = true;
        pos++;
        continue;
      }

      // The number: an optional sign, then everything up to the designator that names the field. The
      // JDK parser owns the grammar of what lies between, so a fraction on a whole-number field or a
      // doubled sign surfaces as a NumberFormatException the caller turns into a clean refusal.
      int start = pos;
      if (value.charAt(pos) == '-' || value.charAt(pos) == '+') {
        pos++;
      }
      while (pos < value.length() && value.charAt(pos) != 'Y' && value.charAt(pos) != 'M'
          && value.charAt(pos) != 'D' && value.charAt(pos) != 'H' && value.charAt(pos) != 'S'
          && value.charAt(pos) != 'T') {
        pos++;
      }
      if (pos == value.length() || pos == start) {
        // A number with no designator to name its field, or a designator with no number.
        throw badLiteral(value);
      }

      String number = value.substring(start, pos);
      char designator = value.charAt(pos);
      pos++;
      anyField = true;
      if (inTime) {
        if (designator == 'H') {
          hours = Math.addExact(hours, nullSafeIntGet(number));
        } else if (designator == 'M') {
          minutes = Math.addExact(minutes, nullSafeIntGet(number));
        } else if (designator == 'S') {
          seconds += nullSafeDoubleGet(number);
        } else {
          throw badLiteral(value);
        }
      } else {
        if (designator == 'Y') {
          years = Math.addExact(years, nullSafeIntGet(number));
        } else if (designator == 'M') {
          months = Math.addExact(months, nullSafeIntGet(number));
        } else if (designator == 'D') {
          days = Math.addExact(days, nullSafeIntGet(number));
        } else {
          throw badLiteral(value);
        }
      }
    }

    if (!anyField && !sawTimeSeparator) {
      // A bare "P". The server takes "PT" as the zero interval but refuses "P", so the T is what
      // makes a field-less literal a duration.
      throw badLiteral(value);
    }

    setValue(years, months, days, hours, minutes, seconds);
  }

  /**
   * Initializes all values of this interval to the specified values.
   *
   * @param years years
   * @param months months
   * @param days days
   * @param hours hours
   * @param minutes minutes
   * @param seconds seconds
   * @see #setValue(int, int, int, int, int, double)
   */
  @SuppressWarnings("method.invocation")
  public PGInterval(int years, int months, int days, int hours, int minutes, double seconds) {
    this();
    setValue(years, months, days, hours, minutes, seconds);
  }

  /**
   * Sets a interval string represented value to this instance. This method only recognize the
   * format, that Postgres returns - not all input formats are supported (e.g. '1 yr 2 m 3 s').
   *
   * @param value String represented interval (e.g. '3 years 2 mons')
   * @throws SQLException Is thrown if the string representation has an unknown format
   */
  @Override
  public void setValue(@Nullable String value) throws SQLException {
    isNull = value == null;
    if (value == null) {
      setValue(0, 0, 0, 0, 0, 0);
      isNull = true;
      return;
    }
    final boolean postgresFormat = !value.startsWith("@");
    // The whole parse runs under one guard: the ISO-8601 branch and the tokenizer branch both extract
    // numbers with Integer.parseInt / Double.parseDouble and slice tokens with substring, so a
    // malformed literal can leak a NumberFormatException or a StringIndexOutOfBoundsException. Surface
    // either as a clean PSQLException with the server's state for a bad interval literal
    // (invalid_datetime_format, 22007), rather than an unchecked exception out of a value read from an
    // untrusted or corrupt wire.
    try {
      if (value.startsWith("P")) {
        parseISO8601Format(value);
        return;
      }
      // Just a simple '0'
      if (!postgresFormat && value.length() == 3 && value.charAt(2) == '0') {
        setValue(0, 0, 0, 0, 0, 0.0);
        return;
      }

      int years = 0;
      int months = 0;
      int days = 0;
      int hours = 0;
      int minutes = 0;
      double seconds = 0;

      // The pending number waiting for the unit word that names its field, and the "ago" terminator
      // the verbose style ends with. A field is a (number, unit) pair, except for the packed
      // hh:mm:ss token, which names its own fields. Fields already filled are tracked because the
      // server names each of them at most once: "1 day 2 days" is a literal it rejects, and
      // overwriting the first value would decode it to a value it can never have sent.
      String valueToken = null;
      int filledFields = 0;
      boolean ago = false;

      String normalized = value.replace('+', ' ').replace('@', ' ').toLowerCase(Locale.ROOT);
      final StringTokenizer st = new StringTokenizer(normalized);
      if (!st.hasMoreTokens()) {
        // No field at all. The server never emits this: an all-zero interval prints as "00:00:00".
        throw badLiteral(value);
      }
      while (st.hasMoreTokens()) {
        String token = st.nextToken();

        if (ago) {
          // "ago" negates the whole interval, so it is the last word of the literal.
          throw badLiteral(value);
        }

        int endHours = token.indexOf(':');
        if (endHours != -1) {
          // The packed hh:mm:ss token, which carries hours, minutes, seconds and microseconds. It
          // stands on its own, so a number still waiting for its unit is a malformed literal, and it
          // fills all three time fields at once, which rules out a second time token and a spelled-out
          // "1 hour" on either side of it.
          if (valueToken != null || (filledFields & TIME_FIELDS) != 0) {
            throw badLiteral(value);
          }
          filledFields |= TIME_FIELDS;

          int offset = token.charAt(0) == '-' ? 1 : 0;

          hours = nullSafeIntGet(token.substring(offset + 0, endHours));
          minutes = nullSafeIntGet(token.substring(endHours + 1, endHours + 3));

          // Pre 7.4 servers do not put second information into the results
          // unless it is non-zero.
          int endMinutes = token.indexOf(':', endHours + 1);
          if (endMinutes != -1) {
            seconds = nullSafeDoubleGet(token.substring(endMinutes + 1));
          }

          if (offset == 1) {
            hours = -hours;
            minutes = -minutes;
            seconds = -seconds;
          }
          continue;
        }

        if (valueToken == null) {
          if (!postgresFormat && "ago".equals(token)) {
            ago = true;
            continue;
          }
          // A number, kept until the unit word that follows names the field it belongs to. It is
          // parsed there, so that the error names the field rather than the bare number.
          valueToken = token;
          continue;
        }

        // The unit word naming the pending number's field. An unknown word, or one naming a field
        // already filled, is a literal the server rejects. Dropping it, as this parser used to,
        // turned any unrecognised text into a zero interval -- "abc" read back as "0 secs".
        int field = unitField(token);
        if (field == 0 || (filledFields & field) != 0) {
          throw badLiteral(value);
        }
        filledFields |= field;

        if (field == YEARS) {
          years = nullSafeIntGet(valueToken);
        } else if (field == MONTHS) {
          months = nullSafeIntGet(valueToken);
        } else if (field == DAYS) {
          days = nullSafeIntGet(valueToken);
        } else if (field == HOURS) {
          hours = nullSafeIntGet(valueToken);
        } else if (field == MINUTES) {
          minutes = nullSafeIntGet(valueToken);
        } else {
          seconds = nullSafeDoubleGet(valueToken);
        }
        valueToken = null;
      }

      if (valueToken != null) {
        // A trailing number with no unit word names the seconds, the way the server reads it:
        // '5'::interval is 00:00:05, and '0'::interval the zero interval. The old parser dropped the
        // number instead, so '5' silently read back as zero. A non-numeric token lands here too and
        // refuses through nullSafeDoubleGet.
        if ((filledFields & SECONDS) != 0) {
          throw badLiteral(value);
        }
        seconds = nullSafeDoubleGet(valueToken);
      }

      if (ago) {
        // Inverse the leading sign
        setValue(-years, -months, -days, -hours, -minutes, -seconds);
      } else {
        setValue(years, months, days, hours, minutes, seconds);
      }
    } catch (NumberFormatException | IndexOutOfBoundsException | ArithmeticException e) {
      throw badLiteral(value, e);
    }
  }

  /**
   * Maps a unit word of a verbose literal to the field it names, or {@code 0} when no field answers
   * to it.
   *
   * <p>The words are matched whole, the way {@code interval_in} matches them. Matching a prefix
   * instead, as this parser used to, accepted units the server has never had: {@code "1 yearsx"} and
   * {@code "1 monsoon"} decoded to a year and a month, though the server rejects both literals.</p>
   *
   * <p>PostgreSQL's own abbreviations are all accepted, including the one that reads against
   * intuition: a bare {@code m} is minutes, not months.</p>
   *
   * @param unit the unit word, already lowercased
   * @return the field's bit, or {@code 0} if the word names no field
   */
  private static int unitField(String unit) {
    switch (unit) {
      case "y":
      case "yr":
      case "yrs":
      case "year":
      case "years":
        return YEARS;
      case "mon":
      case "mons":
      case "month":
      case "months":
        return MONTHS;
      case "d":
      case "day":
      case "days":
        return DAYS;
      case "h":
      case "hr":
      case "hrs":
      case "hour":
      case "hours":
        return HOURS;
      case "m":
      case "min":
      case "mins":
      case "minute":
      case "minutes":
        return MINUTES;
      case "s":
      case "sec":
      case "secs":
      case "second":
      case "seconds":
        return SECONDS;
      default:
        return 0;
    }
  }

  private static PSQLException badLiteral(String value) {
    return badLiteral(value, null);
  }

  private static PSQLException badLiteral(String value, @Nullable Throwable cause) {
    return new PSQLException(GT.tr("Conversion of interval failed: {0}", value),
        PSQLState.BAD_DATETIME_FORMAT, cause);
  }

  /**
   * Set all values of this interval to the specified values.
   *
   * @param years years
   * @param months months
   * @param days days
   * @param hours hours
   * @param minutes minutes
   * @param seconds seconds
   */
  public void setValue(int years, int months, int days, int hours, int minutes, double seconds) {
    setYears(years);
    setMonths(months);
    setDays(days);
    setHours(hours);
    setMinutes(minutes);
    setSeconds(seconds);
  }

  /**
   * Returns the stored interval information as a string.
   *
   * @return String represented interval
   */
  @Override
  public @Nullable String getValue() {
    if (isNull) {
      return null;
    }

    // See https://github.com/pgjdbc/pgjdbc/pull/3866 for the justification
    // It looks like any attempt to estimate the buffer size causes noticeable slowdown
    StringBuilder sb = new StringBuilder(64);
    appendUnit(sb, years, " years");
    appendUnit(sb, months, " mons");
    appendUnit(sb, days, " days");
    appendUnit(sb, hours, " hours");
    appendUnit(sb, minutes, " mins");

    if (sb.length() == 0 || wholeSeconds != 0 || microSeconds != 0) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      if (wholeSeconds < 0 || microSeconds < 0) {
        // E.g. -0.73 has wholeSeconds==0, so we need to check micros as well
        sb.append('-');
      }
      sb.append(Math.abs(wholeSeconds));

      if (microSeconds != 0) {
        sb.append('.');
        int microsStart = sb.length(); // including
        // Add microseconds
        sb.append(Math.abs(microSeconds));
        int microsEnd = sb.length(); // excluding
        int prefixZeros = 6 - (microsEnd - microsStart);
        // Remove trailing zeros
        while (sb.charAt(microsEnd - 1) == '0' && microsEnd > microsStart) {
          microsEnd--;
        }
        sb.setLength(microsEnd);
        // Add missing leading zeros
        sb.insert(microsStart, "000000", 0, prefixZeros);
      }

      sb.append(" secs");
    }
    return sb.toString();
  }

  private static void appendUnit(StringBuilder sb, int value, String unit) {
    if (value == 0) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(' ');
    }
    sb.append(value).append(unit);
  }

  /**
   * Returns the years represented by this interval.
   *
   * @return years represented by this interval
   */
  public int getYears() {
    return years;
  }

  /**
   * Set the years of this interval to the specified value.
   *
   * @param years years to set
   */
  public void setYears(int years) {
    isNull = false;
    this.years = years;
  }

  /**
   * Returns the months represented by this interval.
   *
   * @return months represented by this interval
   */
  public int getMonths() {
    return months;
  }

  /**
   * Set the months of this interval to the specified value.
   *
   * @param months months to set
   */
  public void setMonths(int months) {
    isNull = false;
    this.months = months;
  }

  /**
   * Returns the days represented by this interval.
   *
   * @return days represented by this interval
   */
  public int getDays() {
    return days;
  }

  /**
   * Set the days of this interval to the specified value.
   *
   * @param days days to set
   */
  public void setDays(int days) {
    isNull = false;
    this.days = days;
  }

  /**
   * Returns the hours represented by this interval.
   *
   * @return hours represented by this interval
   */
  public int getHours() {
    return hours;
  }

  /**
   * Set the hours of this interval to the specified value.
   *
   * @param hours hours to set
   */
  public void setHours(int hours) {
    isNull = false;
    this.hours = hours;
  }

  /**
   * Returns the minutes represented by this interval.
   *
   * @return minutes represented by this interval
   */
  public int getMinutes() {
    return minutes;
  }

  /**
   * Set the minutes of this interval to the specified value.
   *
   * @param minutes minutes to set
   */
  public void setMinutes(int minutes) {
    isNull = false;
    this.minutes = minutes;
  }

  /**
   * Returns the seconds represented by this interval.
   *
   * @return seconds represented by this interval
   */
  public double getSeconds() {
    return wholeSeconds + (double) microSeconds / MICROS_IN_SECOND;
  }

  public int getWholeSeconds() {
    return wholeSeconds;
  }

  public int getMicroSeconds() {
    return microSeconds;
  }

  /**
   * Set the seconds of this interval to the specified value.
   *
   * @param seconds seconds to set
   */
  public void setSeconds(double seconds) {
    if (Double.isNaN(seconds)) {
      throw new IllegalArgumentException("Number of seconds must not be NaN");
    }
    // Math.round saturates at Long.MAX_VALUE / Long.MIN_VALUE so the division below
    // cannot overflow even for infinite or extremely large arguments
    long totalMicros = Math.round(seconds * MICROS_IN_SECOND);
    long newWholeSeconds = totalMicros / MICROS_IN_SECOND;
    if (newWholeSeconds < Integer.MIN_VALUE || newWholeSeconds > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Number of whole seconds should be within Integer.MIN_VALUE...Integer.MAX_VALUE");
    }
    isNull = false;
    wholeSeconds = (int) newWholeSeconds;
    microSeconds = (int) (totalMicros % MICROS_IN_SECOND);
  }

  /**
   * Rolls this interval on a given calendar.
   *
   * @param cal Calendar instance to add to
   */
  public void add(Calendar cal) {
    if (isNull) {
      return;
    }

    final int milliseconds = (microSeconds + (microSeconds < 0 ? -500 : 500)) / 1000 + wholeSeconds * 1000;

    cal.add(Calendar.MILLISECOND, milliseconds);
    cal.add(Calendar.MINUTE, getMinutes());
    cal.add(Calendar.HOUR, getHours());
    cal.add(Calendar.DAY_OF_MONTH, getDays());
    cal.add(Calendar.MONTH, getMonths());
    cal.add(Calendar.YEAR, getYears());
  }

  /**
   * Rolls this interval on a given date.
   *
   * @param date Date instance to add to
   */
  @SuppressWarnings("JavaUtilDate")
  public void add(Date date) {
    if (isNull) {
      return;
    }
    final Calendar cal = Calendar.getInstance();
    cal.setTime(date);
    add(cal);
    date.setTime(cal.getTime().getTime());
  }

  /**
   * Add this interval's value to the passed interval. This is backwards to what I would expect, but
   * this makes it match the other existing add methods.
   *
   * @param interval intval to add
   */
  public void add(PGInterval interval) {
    if (isNull || interval.isNull) {
      return;
    }
    interval.setYears(interval.getYears() + getYears());
    interval.setMonths(interval.getMonths() + getMonths());
    interval.setDays(interval.getDays() + getDays());
    interval.setHours(interval.getHours() + getHours());
    interval.setMinutes(interval.getMinutes() + getMinutes());
    interval.setSeconds(interval.getSeconds() + getSeconds());
  }

  /**
   * Scale this interval by an integer factor. The server can scale by arbitrary factors, but that
   * would require adjusting the call signatures for all the existing methods like getDays() or
   * providing our own justification of fractional intervals. Neither of these seem like a good idea
   * without a strong use case.
   *
   * @param factor scale factor
   */
  public void scale(int factor) {
    if (isNull) {
      return;
    }
    setYears(factor * getYears());
    setMonths(factor * getMonths());
    setDays(factor * getDays());
    setHours(factor * getHours());
    setMinutes(factor * getMinutes());
    setSeconds(factor * getSeconds());
  }

  /**
   * Returns integer value of value or 0 if value is null.
   *
   * @param value integer as string value
   * @return integer parsed from string value
   * @throws NumberFormatException if the string contains invalid chars
   */
  private static int nullSafeIntGet(@Nullable String value) throws NumberFormatException {
    if (value == null) {
      return 0;
    }
    NumberParser.requireAsciiLiteral(value);
    return Integer.parseInt(value);
  }

  /**
   * Returns double value of value or 0 if value is null.
   *
   * @param value double as string value
   * @return double parsed from string value
   * @throws NumberFormatException if the string contains invalid chars
   */
  private static double nullSafeDoubleGet(@Nullable String value) throws NumberFormatException {
    if (value == null) {
      return 0;
    }
    NumberParser.requireAsciiLiteral(value);
    return Double.parseDouble(value);
  }

  /**
   * Returns whether an object is equal to this one or not.
   *
   * @param obj Object to compare with
   * @return true if the two intervals are identical
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == null) {
      return false;
    }

    if (obj == this) {
      return true;
    }

    if (!(obj instanceof PGInterval)) {
      return false;
    }

    final PGInterval pgi = (PGInterval) obj;
    if (isNull) {
      return pgi.isNull;
    } else if (pgi.isNull) {
      return false;
    }

    return pgi.years == years
        && pgi.months == months
        && pgi.days == days
        && pgi.hours == hours
        && pgi.minutes == minutes
        && pgi.wholeSeconds == wholeSeconds
        && pgi.microSeconds == microSeconds;
  }

  /**
   * Returns a hashCode for this object.
   *
   * @return hashCode
   */
  @Override
  public int hashCode() {
    if (isNull) {
      return 0;
    }
    return (((((((8 * 31 + microSeconds) * 31 + wholeSeconds) * 31 + minutes) * 31 + hours) * 31
        + days) * 31 + months) * 31 + years) * 31;
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // squid:S2157 "Cloneables" should implement "clone
    return super.clone();
  }
}
