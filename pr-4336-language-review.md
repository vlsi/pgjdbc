# Language review of PR #4336

[PR #4336](https://github.com/pgjdbc/pgjdbc/pull/4336) ("Validate message frame lengths and bound read buffer growth") landed 24 commits, 17 files and about 130 new or rewritten comments. This is a review of the English in that change: the doc comments, the inline comments, the `CHANGELOG.md` entries and the text inside the error and assertion strings. Every comment and every string was read against the code it describes.

Everything below is about what the PR itself added or changed. Defects that were already there before it are out of scope and are not listed, even where the PR rewrote the code around them.

The wording changes live in two branches from the same commit: this one for `src/main` and `CHANGELOG.md`, and `pr-4336-language-review-tests` for `src/test`. This file is not meant to be merged.

## Corrected on these branches

| Where | What it said | What the code does |
| --- | --- | --- |
| `PGStream.MAX_SMALL_MESSAGE_LENGTH` javadoc | the limit applies to "a message carrying no bulk data" | `ErrorResponse` and `NoticeResponse` carry no bulk data either and are read under `MAX_MESSAGE_LENGTH`; `ParameterStatus` is read under `MAX_BUFFERED_MESSAGE_LENGTH`. No criterion separates the six messages on this limit from those, so the list now stands without one. |
| `CHANGELOG.md`, the `08P01` entry | no longer retried under `sslMode=allow` or `sslMode=prefer` | Only `ALLOW` retried it. The `PREFER` branch of `openConnectionImpl` requires `e instanceof SocketTimeoutException`, and a refused length arrived as a plain `IOException`. The code comment beside the retry had the same overstatement. |
| `QueryExecutorImpl.processCopyResults` `@return` (the tag the PR edited) | `null` means the copy ended | A reentrant call also returns `null`, from the `processingCopyResults` guard, while the copy is still running. `startCopy` reads that result through `castNonNull`. |
| `GssEncAction.negotiate`, the token length message | "Backend declared a GSS token of {0} bytes, the maximum is {1}." | The guard is `len < 0 || len > MAX_HANDSHAKE_TOKEN_SIZE`, so it also fires on a negative length, where naming the maximum sends the reader looking for a value that is too large. Now `expected 0 to {1} bytes`. The same applied to the GSS packet message in `GSSInputStream`. |

## Findings left for a maintainer

Ranked by what a reader loses.

### 1. Two refusals the PR changed are still untranslated and concatenated

`QueryExecutorImpl.processResults` throws `pgStream.protocolViolation("Unexpected packet type: " + c)`, and `receiveRFQ` throws `pgStream.protocolViolation("unexpected transaction state in ReadyForQuery message: " + (int) tStatus)`. Both were `new IOException(...)` before the PR and the PR rewrote both lines, so the text is in code it changed. Neither goes through `GT.tr` and both build the message by concatenation, while `processCopyResults` in the same class refuses the same kind of thing with `GT.tr("Unexpected packet type during copy: {0}", Integer.toString(c))` and every catalog under `translation/` carries that msgid.

Two more things about those two strings. `packet` is the word `AGENTS.md` maps to `message`, and the protocol calls the byte a message type. And `+ c` prints the numeric code rather than the character, so a user reads `Unexpected packet type: 90` and has to look up `Z` themselves.

A wording pass cannot repair this: wrapping a string in `GT.tr` is a code change, and it puts a new msgid into the translation catalog, which `AGENTS.md` makes a separate change.

### 2. Two refusals in `GssEncAction.negotiate` leave by different doors

An oversized token goes out through `pgStream.protocolViolation(...)`, which is a bare `IOException` with no SQLState. `run()` catches it and returns it, and `MakeGSS.authenticate` rethrows a returned `IOException` unchanged, so that is what the caller gets. The round limit, a few lines below, returns `new PSQLException(..., PSQLState.PROTOCOL_VIOLATION)`. Both refusals are new in this PR, both mean the same thing, and only one is a `SQLException` a caller can switch on.

The javadoc on `negotiate` classifies the first as `@throws IOException on an I/O error`, which is what the caller will believe when it decides whether to retry.

### 3. `RowDescription` is the one message of its shape left on the general limit

`CopyResponse` and `ParameterDescription` got exact computed limits, `MAX_COPY_RESPONSE_LENGTH` and `MAX_PARAMETER_DESCRIPTION_LENGTH`, each with a javadoc deriving the arithmetic from the unsigned 65535 field count. `RowDescription`, which has the same shape, is read with `PGStream.MAX_MESSAGE_LENGTH`, just under 1 GiB, and is checked only against a minimum: `len - 6 < MIN_FIELD_DESCRIPTION_LENGTH * size`.

There may be a good reason, since a column label is variable-length where a format code and a type OID are not. Nothing says so. Three sibling constants explain themselves in detail and the fourth case is silent, which reads as an omission rather than a decision.

### 4. `QueryExecutorCloseAction.close()` announces a Terminate it does not send

The method opens with `LOGGER.log(Level.FINEST, " FE=> Terminate")`, and the PR added an early return below it for a broken stream, which writes nothing to the socket. The log line therefore records a frontend message that was never sent. The line sat above the other early return before the PR as well, so whoever fixes this is fixing two paths, not one.

The same branch calls `pgStream.getSocket().close()` outside any try/catch, on a path whose stated purpose is releasing a descriptor that `setBroken` may already have failed to close. `abort()`, seventeen lines above, wraps the identical call in `catch (IOException e) { // ignore }`.

### 5. `GSSInputStream.readLength()` refuses a zero-length packet

The guard is `encryptedLength < 1 || encryptedLength > MAX_PAYLOAD_SIZE`. The `CopyData` fix in the same PR goes the other way and accepts an empty body because libpq does. libpq's read path checks only the upper bound, in `pg_GSS_read` (`src/interfaces/libpq/fe-secure-gssapi.c`, `if (input.length > PQ_GSS_MAX_PACKET_SIZE - sizeof(uint32))`), and its `input.length` is unsigned, so the comparison could not be a lower bound there. pgjdbc reads a signed int32 and has to refuse a negative one way or another; the open part is length 0, and it comes down to whether a GSS implementation can emit a zero-length wrapped packet.

### 6. `useRootLocale` in the new tests cannot do what it is there for

`BackendMessageLengthTest`, `MaliciousBackendTest` and `GSSInputStreamTest` each set `Locale.setDefault(Locale.ROOT)` in `@BeforeAll` so that `GT.tr` returns the English their assertions match. `GT` resolves its `ResourceBundle` once, in the constructor of the `private static final GT _gt` field, from `Locale.getDefault(Locale.Category.DISPLAY)` at class-initialization time. Whichever test loads `GT` first fixes the bundle for the whole JVM, so the `@BeforeAll` only works when nothing has used `GT` yet.

It passes today because these tests run early and because most developer machines have no translated bundle installed. On a machine or a CI image with a translated default locale, the assertions that match `"does not fit"` or `"message length"` fail depending on test order. `@Isolated("Uses Locale.setDefault")` on `BackendMessageLengthTest` does not reach this, because the ordering that matters is class loading rather than concurrency.

## Renames the word list asks for, reported and not applied

A comment has to spell an identifier the way the code spells it, so a name that breaks the `AGENTS.md` word list makes the comment beside it break the list too. Every name below is one the PR introduced or in code it rewrote, so the list binds; none of them is renamed on either branch. A rename reaches files a comment batch does not hold, the same spelling usually means something else somewhere, and the comment-only invariant that lets these branches land without a build exists precisely because nothing may touch code.

| Name | Kind | Suggested | Why |
| --- | --- | --- | --- |
| `BackendMessageEnvelopeTest` | class and file | `BackendMessageBoundsTest`, or another name without "envelope" | `AGENTS.md` maps `envelope` to `message`. The class summary has to name the class, which brings the word back. |
| `packetName` | parameter of `PGStream.receiveMessageLength`, 6 uses across `PGStream` and `QueryExecutorImpl` | `messageName` | The protocol, the method, the field it reads and the error text it builds all say message. Note that `MAX_STARTUP_PACKET_LENGTH`, which the neighbouring javadoc cites, is PostgreSQL's own name and must not move. |
| `receiveRFQ` | method, 6 uses in `QueryExecutorImpl`, body rewritten by the PR | `receiveReadyForQuery` | `RFQ` appears nowhere else in the repository, so a reader grepping for the message name does not find the method that reads it. |
| `capsThePreAuthenticationMessageBelowTheBufferedOne` | test method | `keepsThePreAuthenticationMessageBelowTheBufferedOne` | `AGENTS.md` maps `cap` to `limit` as noun and as verb, and binds identifiers. |
| `rejectsAnErrorResponseAboveThePreAuthenticationCap`, `acceptsAnErrorResponseAtThePreAuthenticationCap` | test methods | `...Limit` | Same row. The constant is `MAX_PRE_AUTH_MESSAGE_LENGTH` and its own javadoc calls it a limit. |
| `stopsTheAuthenticationHandshakeAtTheRoundCap`, `stopsTheEncryptionHandshakeAtTheRoundCap` | test methods | `...RoundLimit` | Same row. |
| `stopsAnsweringAfterTheAuthenticationMessageCap` | test method | `stopsAnsweringAfterTheAuthenticationRoundTripLimit` | Besides `cap`, the name calls the thing something the code does not: the constant is `MAX_AUTH_ROUND_TRIPS`, so a reader searching for the round-trip limit does not find this test. |
| `acceptsADataRowThatConsumesItsEnvelopeExactly`, `rejectsADataRowWhoseColumnsUnderrunItsEnvelope` | test methods | `...ItsMessageExactly`, `...UnderrunItsMessage` | `AGENTS.md` maps `envelope` to `message` and gives a test-method rename as its own example. |
| `rejectsAParameterDescriptionWhoseCountDoesNotFillIt` | test method | `rejectsAParameterDescriptionThatCannotHoldItsParameterTypes` | "Fill it" names neither what is counted nor what does not fit. |

Two message names were renamed rather than reported, because they sit inside strings the PR introduced and a string is not a compatibility surface the way an identifier is: "Function call result" became `FunctionCallResponse` and "Copy response" became `CopyResponse`, which is what `receiveMessageLength` is passed on the line above each.

## How this was produced

The `natural-language-sweep` workflow, over the 24 commits of the PR against their merge base. One agent rewrites a batch of comments after reading the code; a second agent that did not write them fills in a fact ledger for the version being replaced and checks every rewrite against the code for lost facts, invented claims and unpaid growth. A comment that contradicts the code is reported rather than rewritten. Everything reported here was then checked by hand against the source before it went in this file.

Two mechanical checks bound what a wording pass can do. In each `docs:` commit the tree is byte-identical to its parent once every comment is stripped. In each string commit it is byte-identical once every string literal is blanked, which proves the edits stayed inside the quotes but nothing about what is inside them; the commit messages carry the resulting text for that reason. `BackendMessageLengthTest`, `MaliciousBackendTest`, `VisibleBufferedInputStreamTest`, `BackendMessageEnvelopeTest`, `GSSInputStreamTest` and `GssHandshakeLoopTest` pass on each branch separately, which is the check that matters for the `MessageFormat` patterns: an apostrophe inside one of those strings would stop the placeholders from being substituted without failing any build.
