/* Copyright (c) 2001-2022, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.jdbc.JDBCConnection;
import org.hsqldb.jdbc.JDBCDriver;
import org.hsqldb.lib.ArrayUtil;
import org.hsqldb.lib.CountUpDownLatch;
import org.hsqldb.lib.HashMap;
import org.hsqldb.lib.HsqlArrayList;
import org.hsqldb.lib.HsqlDeque;
import org.hsqldb.lib.Iterator;
import org.hsqldb.lib.OrderedHashSet;
import org.hsqldb.lib.SimpleLog;
import org.hsqldb.map.ValuePool;
import org.hsqldb.navigator.RowSetNavigator;
import org.hsqldb.navigator.RowSetNavigatorClient;
import org.hsqldb.persist.HsqlDatabaseProperties;
import org.hsqldb.persist.HsqlProperties;
import org.hsqldb.persist.PersistentStore;
import org.hsqldb.result.Result;
import org.hsqldb.result.ResultConstants;
import org.hsqldb.result.ResultLob;
import org.hsqldb.result.ResultProperties;
import org.hsqldb.rights.Grantee;
import org.hsqldb.rights.User;
import org.hsqldb.types.BlobDataID;
import org.hsqldb.types.ClobDataID;
import org.hsqldb.types.DateTimeType;
import org.hsqldb.types.TimeData;
import org.hsqldb.types.TimestampData;
import org.hsqldb.types.Type;
import org.hsqldb.types.TypedComparator;

/**
 * Implementation of SQL sessions.
 *
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.7.0
 * @since 1.7.0
 */
public class Session implements SessionInterface {

    //
    private volatile boolean isClosed;

    //
    public Database    database;
    private final User sessionUser;
    private User       user;
    private Grantee    role;

    // transaction support
    public boolean          isReadOnlyDefault;
    int isolationLevelDefault = SessionInterface.TX_READ_COMMITTED;
    int isolationLevel        = SessionInterface.TX_READ_COMMITTED;
    boolean                 isReadOnlyIsolation;
    int                     actionIndex;
    long                    actionStartTimestamp;
    long                    actionTimestamp;
    long                    statementStartTimestamp;
    long                    transactionTimestamp;
    long                    transactionEndTimestamp;
    final boolean           txConflictRollback;
    final boolean           txInterruptRollback;
    boolean                 isPreTransaction;
    boolean                 isTransaction;
    boolean                 isBatch;
    volatile boolean        abortAction;
    volatile boolean        abortTransaction;
    volatile boolean        redoAction;
    HsqlArrayList           rowActionList;
    volatile boolean        tempUnlocked;
    public OrderedHashSet   waitedSessions;
    public OrderedHashSet   waitingSessions;
    OrderedHashSet          tempSet;
    OrderedHashSet          actionSet;
    public CountUpDownLatch latch = new CountUpDownLatch();
    TimeoutManager          timeoutManager;

    // current settings
    final TimeZone              timeZone;
    TimeZone                    currentTimeZone;
    final String                zoneString;
    boolean                     isNetwork;
    private int                 sessionMaxRows;
    int                         sessionOptimization = 8;
    private final long          sessionId;
    private boolean             ignoreCase;
    private final TimestampData connectTimestamp;

    // internal connection
    private JDBCConnection intConnection;

    // external connection
    private JDBCConnection extConnection;

    // schema
    public HsqlName currentSchema;
    public HsqlName loggedSchema;

    // query processing
    ParserCommand         parser;
    boolean               isProcessingScript;
    boolean               isProcessingLog;
    public SessionContext sessionContext;
    int                   resultMaxMemoryRows;

    //
    public SessionData sessionData;

    //
    public StatementManager statementManager;

    //
    public Object special;

    /**
     * Constructs a new Session object.
     * @param  db the database to which this represents a connection
     * @param  user the initial user
     * @param  autocommit the initial autocommit value
     * @param  readonly the initial readonly value
     * @param  id the session identifier, as known to the database
     * @param  zone the TimeZone
     */
    Session(Database db, User user, boolean autocommit, boolean readonly,
            long id, TimeZone zone) {

        this.database         = db;
        this.sessionUser      = user;
        this.user             = user;
        this.sessionId        = id;
        this.timeZone         = zone;
        this.currentTimeZone  = zone;
        this.zoneString       = zone.getID();
        rowActionList         = new HsqlArrayList(128, true);
        waitedSessions        = new OrderedHashSet();
        waitingSessions       = new OrderedHashSet();
        tempSet               = new OrderedHashSet();
        actionSet             = new OrderedHashSet();
        isolationLevelDefault = database.defaultIsolationLevel;
        ignoreCase            = database.sqlIgnoreCase;
        isolationLevel        = isolationLevelDefault;
        txConflictRollback    = database.txConflictRollback;
        txInterruptRollback   = database.txInterruptRollback;
        isReadOnlyDefault     = readonly;
        isReadOnlyIsolation = isolationLevel
                              == SessionInterface.TX_READ_UNCOMMITTED;
        sessionContext              = new SessionContext(this);
        sessionContext.isAutoCommit = autocommit;
        sessionContext.isReadOnly   = isReadOnlyDefault;
        parser                      = new ParserCommand(this, new Scanner());
        sessionData                 = new SessionData(database, this);
        statementManager            = new StatementManager(this);
        timeoutManager              = new TimeoutManager();

        setResultMemoryRowCount(database.getResultMaxMemoryRows());
        resetSchema();

        connectTimestamp = getCurrentTimestamp();
    }

    void resetSchema() {
        loggedSchema  = null;
        currentSchema = user.getInitialOrDefaultSchema();
    }

    /**
     *  Retrieves the session identifier for this Session.
     *
     * @return the session identifier for this Session
     */
    public long getId() {
        return sessionId;
    }

    public int getRandomId() {
        return randomId;
    }

    /**
     * Closes this Session.
     */
    public synchronized void close() {

        if (isClosed) {
            return;
        }

        rollback(false);

        try {
            database.logger.writeOtherStatement(this, Tokens.T_DISCONNECT);
        } catch (HsqlException e) {}

        sessionData.closeAllNavigators();
        sessionData.persistentStoreCollection.release();
        statementManager.reset();

        // keep sessionContext and sessionData
        rowActionList.clear();

        isClosed                    = true;
        user                        = null;
        sessionContext.savepoints   = null;
        sessionContext.lastIdentity = null;
        intConnection               = null;

        database.sessionManager.removeSession(this);
        database.closeIfLast();

        database = null;
    }

    /**
     * Retrieves whether this Session is closed.
     *
     * @return true if this Session is closed
     */
    public boolean isClosed() {
        return isClosed;
    }

    public synchronized void setIsolationDefault(int level) {

        if (level == SessionInterface.TX_READ_UNCOMMITTED) {
            level = SessionInterface.TX_READ_COMMITTED;
        }

        if (level == isolationLevelDefault) {
            return;
        }

        isolationLevelDefault = level;

        if (!isInMidTransaction()) {
            isolationLevel = isolationLevelDefault;
            isReadOnlyIsolation = level
                                  == SessionInterface.TX_READ_UNCOMMITTED;
        }
    }

    /**
     * sets ISOLATION for the next transaction only
     */
    public void setIsolation(int level) {

        if (isInMidTransaction()) {
            throw Error.error(ErrorCode.X_25001);
        }

        if (level == SessionInterface.TX_READ_UNCOMMITTED) {
            level = SessionInterface.TX_READ_COMMITTED;
        }

        if (isolationLevel != level) {
            isolationLevel = level;
            isReadOnlyIsolation = level
                                  == SessionInterface.TX_READ_UNCOMMITTED;
        }
    }

    public synchronized int getIsolation() {
        return isolationLevel;
    }

    /**
     * Setter for iLastIdentity attribute.
     *
     * @param  i the new value
     */
    void setLastIdentity(Number i) {
        sessionContext.lastIdentity = i;
    }

    /**
     * Getter for iLastIdentity attribute.
     *
     * @return the current value
     */
    public Number getLastIdentity() {
        return sessionContext.lastIdentity;
    }

    /**
     * Retrieves the Database instance to which this
     * Session represents a connection.
     *
     * @return the Database object to which this Session is connected
     */
    public Database getDatabase() {
        return database;
    }

    /**
     * Retrieves the name, as known to the database, of the
     * user currently controlling this Session.
     *
     * @return the name of the user currently connected within this Session
     */
    public String getUsername() {
        return user.getName().getNameString();
    }

    /**
     * Retrieves the User object representing the user currently controlling
     * this Session.
     *
     * @return this Session's User object
     */
    public User getUser() {
        return user;
    }

    public Grantee getGrantee() {
        return user;
    }

    public Grantee getRole() {
        return role;
    }

    /**
     * Sets this Session's User object to the one specified by the
     * user argument.
     *
     * @param  user the new User object for this session
     */
    public void setUser(User user) {
        this.user = user;
    }

    public void setRole(Grantee role) {
        this.role = role;
    }

    int getMaxRows() {
        return sessionContext.currentMaxRows;
    }

    /**
     * The SQL command SET MAXROWS n will override the Statement.setMaxRows(n)
     * for the next direct statement only
     *
     * NB this is dedicated to the SET MAXROWS sql statement and should not
     * otherwise be called. (fredt@users)
     */
    void setSQLMaxRows(int rows) {
        sessionMaxRows = rows;
    }

    void setFeature(String feature, boolean value) {

        int number = 8;

        if (value) {
            sessionOptimization |= number;
        } else {
            sessionOptimization &= ~number;
        }
    }

    /**
     * Checks whether this Session's current User has the privileges of
     * the ADMIN role.
     */
    void checkAdmin() {
        user.checkAdmin();
    }

    /**
     * This is used for reading - writing to existing tables.
     */
    void checkReadWrite() {

        if (sessionContext.isReadOnly || isReadOnlyIsolation) {
            throw Error.error(ErrorCode.X_25006);
        }
    }

    /**
     * This is used for creating new database objects such as tables.
     */
    void checkDDLWrite() {

        if (isProcessingScript || isProcessingLog) {
            return;
        }

        checkReadWrite();
    }

    public long getActionTimestamp() {
        return actionTimestamp;
    }

    /**
     *  Adds a delete action to the row and the transaction manager.
     *
     * @param  table the table of the row
     * @param  row the deleted row
     */
    public void addDeleteAction(Table table, PersistentStore store, Row row,
                                int[] changedColumns) {

//        tempActionHistory.add("add delete action " + actionTimestamp);
        if (abortTransaction) {
            throw Error.error(ErrorCode.X_40001);
        }

        if (abortAction) {
            throw Error.error(ErrorCode.X_40502);
        }

        database.txManager.addDeleteAction(this, table, store, row,
                                           changedColumns);
    }

    void addInsertAction(Table table, PersistentStore store, Row row,
                         int[] changedColumns) {

//        tempActionHistory.add("add insert to transaction " + actionTimestamp);
        database.txManager.addInsertAction(this, table, store, row,
                                           changedColumns);

        // abort only after adding so that the new row gets removed from indexes
        if (abortTransaction) {
            throw Error.error(ErrorCode.X_40001);
        }

        if (abortAction) {
            throw Error.error(ErrorCode.X_40502);
        }
    }

    public HsqlArrayList getRowActionList() {
        return rowActionList;
    }

    /**
     *  Setter for the autocommit attribute.
     *
     * @param  autocommit the new value
     */
    public synchronized void setAutoCommit(boolean autocommit) {

        if (isClosed) {
            return;
        }

        if (sessionContext.depth > 0) {
            return;
        }

        if (sessionContext.isAutoCommit != autocommit) {
            commit(false);

            sessionContext.isAutoCommit = autocommit;
        }
    }

    public void setAutoCommitRows(int rows) {

        if (isClosed) {
            return;
        }

        if (sessionContext.depth > 0) {
            return;
        }

        sessionContext.autoCommitRows = rows;
    }

    public void beginAction(Statement cs) {

        actionIndex = rowActionList.size();

        database.txManager.beginAction(this, cs);
        database.txManager.beginActionResume(this);
    }

    public void endAction(Result result) {

//        tempActionHistory.add("endAction " + actionTimestamp);
        abortAction = false;

        timeoutManager.endTimeout();
        sessionData.persistentStoreCollection.clearStatementTables();

        if (result.mode == ResultConstants.ERROR) {
            sessionData.persistentStoreCollection.clearResultTables(
                actionTimestamp);
            database.txManager.rollbackAction(this);
        } else {
            sessionContext
                .diagnosticsVariables[ExpressionColumn.idx_row_count] =
                    result.mode == ResultConstants.UPDATECOUNT
                    ? Integer.valueOf(result.getUpdateCount())
                    : ValuePool.INTEGER_0;

            database.txManager.completeActions(this);
        }

//        tempActionHistory.add("endAction ends " + actionTimestamp);
    }

    /**
     * Explicit start of transaction by user
     */
    public void startTransaction() {
        database.txManager.beginTransaction(this);
    }

    public synchronized void startPhasedTransaction() {}

    /*
     * @todo - fredt - for two phased pre-commit - after this call, further
     * state changing calls should fail
     */
    public synchronized void prepareCommit() {

        if (isClosed) {
            throw Error.error(ErrorCode.X_08003);
        }

        if (!database.txManager.prepareCommitActions(this)) {

//            tempActionHistory.add("commit aborts " + actionTimestamp);
            rollbackNoCheck(false);

            throw Error.error(ErrorCode.X_40001);
        }
    }

    /**
     * Commits any uncommitted transaction this Session may have open
     */
    public synchronized void commit(boolean chain) {

//        tempActionHistory.add("commit " + actionTimestamp);
        if (isClosed) {
            return;
        }

        if (sessionContext.depth > 0) {
            return;
        }

        if (isTransaction) {
            if (!database.txManager.commitTransaction(this)) {

                // tempActionHistory.add("commit aborts " + actionTimestamp);
                rollbackNoCheck(chain);

                throw Error.error(ErrorCode.X_40001);
            }
        } else {
            logSequences();
        }

        endTransaction(true, chain);

        if (database != null && !sessionUser.isSystem()
                && database.logger.needsCheckpointReset()) {
            database.checkpointRunner.start();
        }
    }

    /**
     * Rolls back any uncommitted transaction this Session may have open.
     */
    public synchronized void rollback(boolean chain) {

        //        tempActionHistory.add("rollback " + actionTimestamp);
        if (sessionContext.depth > 0) {
            return;
        }

        rollbackNoCheck(chain);
    }

    synchronized void rollbackNoCheck(boolean chain) {

        if (isClosed) {
            return;
        }

        if (isTransaction) {
            database.txManager.rollback(this);
        }

        endTransaction(false, chain);
    }

    private void endTransaction(boolean commit, boolean chain) {

        abortAction       = false;
        abortTransaction  = false;
        transactionUTCSet = false;

        sessionContext.resetStack();
        sessionContext.savepoints.clear();
        sessionContext.savepointTimestamps.clear();
        rowActionList.clear();
        sessionData.persistentStoreCollection.clearTransactionTables();
        sessionData.closeAllTransactionNavigators();

        if (!chain) {
            sessionContext.isReadOnly = isReadOnlyDefault;

            setIsolation(isolationLevelDefault);
        }

        if (database.logger.getSqlEventLogLevel() > 0) {
            Statement endTX = commit ? StatementSession.commitNoChainStatement
                                     : StatementSession
                                         .rollbackNoChainStatement;

            database.logger.logStatementEvent(this, endTX, null,
                                              Result.updateZeroResult,
                                              SimpleLog.LOG_ERROR);
        }
/* debug 190
        tempActionHistory.add("commit ends " + actionTimestamp);
        tempActionHistory.clear();
//*/
    }

    /**
     * Clear structures and reset variables to original. For JDBC use only.
     * Note: sets autocommit true
     */
    public synchronized void resetSession() {

        if (isClosed) {
            return;
        }

        rollbackNoCheck(false);
        sessionData.closeAllNavigators();
        sessionData.persistentStoreCollection.clearAllTables();
        statementManager.reset();

        sessionContext.lastIdentity = ValuePool.INTEGER_0;
        sessionContext.isAutoCommit = true;

        setResultMemoryRowCount(database.getResultMaxMemoryRows());

        user = sessionUser;

        resetSchema();
        resetTimeZone();

        sessionMaxRows = 0;
        ignoreCase     = database.sqlIgnoreCase;

        setIsolation(isolationLevelDefault);
    }

    /**
     *  Registers a transaction SAVEPOINT. A new SAVEPOINT with the
     *  name of an existing one replaces the old SAVEPOINT.
     *
     * @param  name name of the savepoint
     */
    public synchronized void savepoint(String name) {

        int index = sessionContext.savepoints.getIndex(name);

        if (index != -1) {
            sessionContext.savepoints.remove(name);
            sessionContext.savepointTimestamps.remove(index);
        }

        actionTimestamp = database.txManager.getNextGlobalChangeTimestamp();

        sessionContext.savepoints.add(name,
                                      ValuePool.getInt(rowActionList.size()));
        sessionContext.savepointTimestamps.addLast(actionTimestamp);
    }

    /**
     *  Performs a partial transaction ROLLBACK to savepoint.
     *
     * @param  name name of savepoint
     */
    public synchronized void rollbackToSavepoint(String name) {

        if (isClosed) {
            return;
        }

        int index = sessionContext.savepoints.getIndex(name);

        if (index < 0) {
            throw Error.error(ErrorCode.X_3B001, name);
        }

        database.txManager.rollbackSavepoint(this, index);
    }

    /**
     * Performs a partial transaction ROLLBACK of current savepoint level.
     */
    public synchronized void rollbackToSavepoint() {

        if (isClosed) {
            return;
        }

        database.txManager.rollbackSavepoint(this, 0);
    }

    public synchronized void rollbackAction(int start, long timestamp) {

        if (isClosed) {
            return;
        }

        database.txManager.rollbackPartial(this, start, timestamp);
    }

    /**
     * Releases a savepoint
     *
     * @param  name name of savepoint
     */
    public synchronized void releaseSavepoint(String name) {

        // remove this and all later savepoints
        int index = sessionContext.savepoints.getIndex(name);

        if (index < 0) {
            throw Error.error(ErrorCode.X_3B001, name);
        }

        while (sessionContext.savepoints.size() > index) {
            sessionContext.savepoints.removeEntry(
                sessionContext.savepoints.size() - 1);
            sessionContext.savepointTimestamps.removeLast();
        }
    }

    public boolean isInMidTransaction() {
        return isTransaction;
    }

    public void setNoSQL() {
        sessionContext.noSQL = true;
    }

    public void setIgnoreCase(boolean mode) {
        ignoreCase = mode;
    }

    public boolean isIgnorecase() {
        return ignoreCase;
    }

    /**
     * sets READ ONLY for next transaction / subtransaction only
     *
     * @param  readonly the new value
     */
    public void setReadOnly(boolean readonly) {

        if (!readonly && database.databaseReadOnly) {
            throw Error.error(ErrorCode.DATABASE_IS_READONLY);
        }

        if (isInMidTransaction()) {
            throw Error.error(ErrorCode.X_25001);
        }

        sessionContext.isReadOnly = readonly;
    }

    public synchronized void setReadOnlyDefault(boolean readonly) {

        if (!readonly && database.databaseReadOnly) {
            throw Error.error(ErrorCode.DATABASE_IS_READONLY);
        }

        isReadOnlyDefault = readonly;

        if (!isInMidTransaction()) {
            sessionContext.isReadOnly = isReadOnlyDefault;
        }
    }

    /**
     *  Getter for readonly attribute.
     *
     * @return the current value
     */
    public boolean isReadOnly() {
        return sessionContext.isReadOnly || isReadOnlyIsolation;
    }

    public synchronized boolean isReadOnlyDefault() {
        return isReadOnlyDefault;
    }

    /**
     *  Getter for autoCommit attribute.
     *
     * @return the current value
     */
    public synchronized boolean isAutoCommit() {
        return sessionContext.isAutoCommit;
    }

    public synchronized int getStreamBlockSize() {
        return lobStreamBlockSize;
    }

    /**
     * Retrieves an internal Connection object equivalent to the one
     * that created this Session.
     *
     * @return  internal connection.
     */
    JDBCConnection getInternalConnection() {

        if (intConnection == null) {
            intConnection = new JDBCConnection(this);
        }

        JDBCDriver.driverInstance.threadConnection.set(intConnection);

        return intConnection;
    }

    void releaseInternalConnection() {

        if (sessionContext.depth == 0) {
            JDBCDriver.driverInstance.threadConnection.set(null);
        }
    }

    /**
     * Retrieves the external JDBC connection
     */
    public JDBCConnection getJDBCConnection() {
        return extConnection;
    }

    public void setJDBCConnection(JDBCConnection connection) {
        extConnection = connection;
    }

    public String getDatabaseUniqueName() {
        return database.getNameString();
    }

// campbell-burnet@users 20020810 metadata 1.7.2
//----------------------------------------------------------------
// more efficient for MetaData concerns than checkAdmin

    /**
     * Getter for admin attribute.
     *
     * @return the current value
     */
    public boolean isAdmin() {
        return user.isAdmin();
    }

    /**
     * Getter for connectTime attribute.
     *
     * @return the value
     */
    public TimestampData getConnectTimestamp() {
        return connectTimestamp;
    }

    /**
     * Count of actions in current transaction.
     *
     * @return the current value
     */
    public int getTransactionSize() {
        return rowActionList.size();
    }

    public long getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public Statement compileStatement(String sql, int props) {

        parser.reset(this, sql);

        Statement cs = parser.compileStatement(props);

        return cs;
    }

    public Statement compileStatement(String sql) {

        parser.reset(this, sql);

        Statement cs =
            parser.compileStatement(ResultProperties.defaultPropsValue);

        cs.setCompileTimestamp(Long.MAX_VALUE);

        return cs;
    }

    /**
     * Executes the command encapsulated by the cmd argument.
     *
     * @param cmd the command to execute
     * @return the result of executing the command
     */
    public synchronized Result execute(Result cmd) {

        if (isClosed) {
            return Result.newErrorResult(Error.error(ErrorCode.X_08503));
        }

        sessionContext.currentMaxRows = 0;
        isBatch                       = false;

        switch (cmd.mode) {

            case ResultConstants.LARGE_OBJECT_OP : {
                return performLOBOperation((ResultLob) cmd);
            }
            case ResultConstants.EXECUTE : {
                int maxRows = cmd.getUpdateCount();

                if (maxRows == -1) {
                    sessionContext.currentMaxRows = 0;
                } else {
                    sessionContext.currentMaxRows = maxRows;
                }

                Statement cs = cmd.statement;

                if (cs == null
                        || cs.compileTimestamp
                           < database.schemaManager.schemaChangeTimestamp) {
                    long csid = cmd.getStatementID();

                    cs = statementManager.getStatement(csid);

                    cmd.setStatement(cs);

                    if (cs == null) {

                        // invalid sql has been removed already
                        return Result.newErrorResult(
                            Error.error(ErrorCode.X_07502));
                    }
                }

                Object[] pvals = (Object[]) cmd.valueData;
                Result result = executeCompiledStatement(cs, pvals,
                    cmd.queryTimeout);

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.BATCHEXECUTE : {
                isBatch = true;

                Result result = executeCompiledBatchStatement(cmd);

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.EXECDIRECT : {
                Result result = executeDirectStatement(cmd);

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.BATCHEXECDIRECT : {
                isBatch = true;

                Result result = executeDirectBatchStatement(cmd);

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.PREPARE : {
                Statement cs;

                try {
                    cs = statementManager.compile(cmd);
                } catch (Throwable t) {
                    String errorString = cmd.getMainString();

                    return Result.newErrorResult(t, errorString);
                }

                Result result = Result.newPrepareResponse(cs);

                if (cs.getType() == StatementTypes.SELECT_CURSOR
                        || cs.getType() == StatementTypes.CALL) {
                    sessionData.setResultSetProperties(cmd, result);
                }

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.CLOSE_RESULT : {
                closeNavigator(cmd.getResultId());

                return Result.updateZeroResult;
            }
            case ResultConstants.UPDATE_RESULT : {
                Result result = this.executeResultUpdate(cmd);

                result = performPostExecute(cmd, result);

                return result;
            }
            case ResultConstants.FREESTMT : {
                statementManager.freeStatement(cmd.getStatementID());

                return Result.updateZeroResult;
            }
            case ResultConstants.GETSESSIONATTR : {
                int id = cmd.getStatementType();

                return getAttributesResult(id);
            }
            case ResultConstants.SETSESSIONATTR : {
                return setAttributes(cmd);
            }
            case ResultConstants.ENDTRAN : {
                switch (cmd.getActionType()) {

                    case ResultConstants.TX_COMMIT :
                        try {
                            commit(false);
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }
                        break;

                    case ResultConstants.TX_COMMIT_AND_CHAIN :
                        try {
                            commit(true);
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }
                        break;

                    case ResultConstants.TX_ROLLBACK :
                        rollback(false);
                        break;

                    case ResultConstants.TX_ROLLBACK_AND_CHAIN :
                        rollback(true);
                        break;

                    case ResultConstants.TX_SAVEPOINT_NAME_RELEASE :
                        try {
                            String name = cmd.getMainString();

                            releaseSavepoint(name);
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }
                        break;

                    case ResultConstants.TX_SAVEPOINT_NAME_ROLLBACK :
                        try {
                            rollbackToSavepoint(cmd.getMainString());
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }
                        break;

                    case ResultConstants.PREPARECOMMIT :
                        try {
                            prepareCommit();
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }
                        break;
                }

                return Result.updateZeroResult;
            }
            case ResultConstants.SETCONNECTATTR : {
                switch (cmd.getConnectionAttrType()) {

                    case ResultConstants.SQL_ATTR_SAVEPOINT_NAME :
                        try {
                            savepoint(cmd.getMainString());
                        } catch (Throwable t) {
                            return Result.newErrorResult(t);
                        }

                    // case ResultConstants.SQL_ATTR_AUTO_IPD
                    //   - always true
                    // default: throw - case never happens
                }

                return Result.updateZeroResult;
            }
            case ResultConstants.REQUESTDATA : {
                return sessionData.getDataResultSlice(cmd.getResultId(),
                                                      cmd.getUpdateCount(),
                                                      cmd.getFetchSize());
            }
            case ResultConstants.DISCONNECT : {
                close();

                return Result.updateZeroResult;
            }
            default : {
                return Result.newErrorResult(
                    Error.runtimeError(ErrorCode.U_S0500, "Session"));
            }
        }
    }

    private Result performPostExecute(Result command, Result result) {

        if (result.mode == ResultConstants.DATA) {
            result = sessionData.getDataResultHead(command, result, isNetwork);
        }

/*
        else if (result.mode == ResultConstants.ERROR) {
            while (sessionContext.depth > 0) {
                sessionContext.pop();
            }
        }
*/
        if (sqlWarnings != null && sqlWarnings.size() > 0) {
            if (result.mode == ResultConstants.UPDATECOUNT) {
                result = new Result(ResultConstants.UPDATECOUNT,
                                    result.getUpdateCount());
            }

            HsqlException[] warnings = getAndClearWarnings();

            result.addWarnings(warnings);
        }

        return result;
    }

    public RowSetNavigatorClient getRows(long navigatorId, int offset,
                                         int blockSize) {
        return sessionData.getRowSetSlice(navigatorId, offset, blockSize);
    }

    public synchronized void closeNavigator(long id) {
        sessionData.closeNavigator(id);
    }

    public Result executeDirectStatement(Result cmd) {

        String        sql = cmd.getMainString();
        HsqlArrayList list;
        int           maxRows = cmd.getUpdateCount();

        if (maxRows == -1) {
            sessionContext.currentMaxRows = 0;
        } else if (sessionMaxRows == 0) {
            sessionContext.currentMaxRows = maxRows;
        } else {
            sessionContext.currentMaxRows = sessionMaxRows;
            sessionMaxRows                = 0;
        }

        try {
            list = parser.compileStatements(sql, cmd);
        } catch (Throwable e) {
            return Result.newErrorResult(e);
        }

        Result   result         = null;
        boolean  recompile      = false;
        HsqlName originalSchema = getCurrentSchemaHsqlName();

        for (int i = 0; i < list.size(); i++) {
            Statement cs = (Statement) list.get(i);

            if (i > 0) {
                if (cs.getCompileTimestamp()
                        > database.txManager.getGlobalChangeTimestamp()) {
                    recompile = true;
                }

                if (cs.getSchemaName() != null
                        && cs.getSchemaName() != originalSchema) {
                    recompile = true;
                }
            }

            if (recompile) {
                cs = compileStatement(cs.getSQL(), cmd.getExecuteProperties());
            }

            cs.setGeneratedColumnInfo(cmd.getGeneratedResultType(),
                                      cmd.getGeneratedResultMetaData());

            result = executeCompiledStatement(cs, ValuePool.emptyObjectArray,
                                              cmd.queryTimeout);

            if (result.mode == ResultConstants.ERROR) {
                break;
            }
        }

        return result;
    }

    public Result executeDirectStatement(String sql) {

        try {
            Statement cs = compileStatement(sql);
            Result result = executeCompiledStatement(cs,
                ValuePool.emptyObjectArray, 0);

            return result;
        } catch (HsqlException e) {
            return Result.newErrorResult(e);
        }
    }

    public Result executeCompiledStatement(Statement cs, Object[] pvals,
                                           int timeout) {

        Result r;

        if (abortTransaction) {
            return handleAbortTransaction();
        }

        if (sessionContext.depth > 0) {
            if (sessionContext.noSQL || cs.isAutoCommitStatement()) {
                return Result.newErrorResult(Error.error(ErrorCode.X_46000));
            }
        }

        if (cs.isAutoCommitStatement()) {
            if (isReadOnly()) {
                return Result.newErrorResult(Error.error(ErrorCode.X_25006));
            }

            try {

                /* special autocommit for backward compatibility */
                commit(false);
            } catch (HsqlException e) {
                database.logger.logInfoEvent("Exception at commit");
            }
        }

        sessionContext.currentStatement = cs;
        statementStartTimestamp =
            database.txManager.getGlobalChangeTimestamp();

        boolean isTX = cs.isTransactionStatement();

        if (!isTX) {
            actionTimestamp =
                database.txManager.getNextGlobalChangeTimestamp();

            sessionContext.setDynamicArguments(pvals);

            // statements such as DISCONNECT may close the session
            if (database.logger.getSqlEventLogLevel()
                    >= SimpleLog.LOG_NORMAL) {
                database.logger.logStatementEvent(this, cs, pvals,
                                                  Result.updateZeroResult,
                                                  SimpleLog.LOG_NORMAL);
            }

            r                               = cs.execute(this);
            sessionContext.currentStatement = null;
            abortAction                     = false;

            sessionData.persistentStoreCollection.clearStatementTables();

            return r;
        }

        timeoutManager.startTimeout(timeout);

        repeatLoop:
        while (true) {
            actionIndex = rowActionList.size();

            database.txManager.beginAction(this, cs);

            if (redoAction) {
                redoAction = false;

                continue;
            }

            cs = sessionContext.currentStatement;

            if (cs == null) {
                return Result.newErrorResult(Error.error(ErrorCode.X_07502));
            }

            if (abortTransaction) {
                return handleAbortTransaction();
            }

            boolean interrupted = false;

            while (true) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    interrupted = txInterruptRollback;

                    Thread.interrupted();

                    continue;
                }

                break;
            }

            if (abortAction) {
                r = Result.newErrorResult(Error.error(ErrorCode.X_40502));

                endAction(r);

                break repeatLoop;
            }

            if (abortTransaction || interrupted) {
                Result result = handleAbortTransaction();

                if (interrupted) {
                    Thread.currentThread().interrupt();
                }

                return result;
            }

            database.txManager.beginActionResume(this);

            //        tempActionHistory.add("sql execute " + cs.sql + " " + actionTimestamp + " " + rowActionList.size());
            sessionContext.setDynamicArguments(pvals);

            r = cs.execute(this);

            if (database.logger.getSqlEventLogLevel()
                    >= SimpleLog.LOG_NORMAL) {
                database.logger.logStatementEvent(this, cs, pvals, r,
                                                  SimpleLog.LOG_NORMAL);
            }

            //        tempActionHistory.add("sql execute end " + actionTimestamp + " " + rowActionList.size());
            endAction(r);

            if (abortTransaction) {
                break repeatLoop;
            }

            if (redoAction) {
                redoAction = false;

                while (true) {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.interrupted();

                        continue;
                    }

                    break;
                }
            } else {
                break repeatLoop;
            }
        }

        if (abortTransaction) {
            return handleAbortTransaction();
        }

        if (sessionContext.depth == 0) {
            if (sessionContext.isAutoCommit || cs.isAutoCommitStatement()) {
                try {
                    if (r.mode == ResultConstants.ERROR) {
                        rollbackNoCheck(false);
                    } else {
                        commit(false);
                    }
                } catch (Exception e) {
                    sessionContext.currentStatement = null;

                    return Result.newErrorResult(Error.error(ErrorCode.X_40001,
                            e));
                }
            } else if (sessionContext.autoCommitRows > 0) {
                if (rowActionList.size() > sessionContext.autoCommitRows) {
                    try {
                        commit(false);
                    } catch (Exception e) {
                        sessionContext.currentStatement = null;

                        return Result.newErrorResult(
                            Error.error(ErrorCode.X_40001, e));
                    }
                }
            }
        }

        sessionContext.currentStatement = null;

        return r;
    }

    private Result handleAbortTransaction() {

        rollbackNoCheck(false);

        sessionContext.currentStatement = null;

        return Result.newErrorResult(Error.error(ErrorCode.X_40001));
    }

    private Result executeCompiledBatchStatement(Result cmd) {

        long      csid;
        Statement cs;
        int[]     updateCounts;
        int       count;

        cs = cmd.statement;

        if (cs == null
                || cs.compileTimestamp
                   < database.schemaManager.schemaChangeTimestamp) {
            csid = cmd.getStatementID();
            cs   = statementManager.getStatement(csid);

            if (cs == null) {

                // invalid sql has been removed already
                return Result.newErrorResult(Error.error(ErrorCode.X_07502));
            }
        }

        count = 0;

        RowSetNavigator nav = cmd.initialiseNavigator();

        updateCounts = new int[nav.getSize()];

        Result generatedResult = null;

        if (cs.hasGeneratedColumns()) {
            generatedResult =
                Result.newGeneratedDataResult(cs.generatedResultMetaData());
        }

        Result error = null;

        while (nav.next()) {
            Object[] pvals = nav.getCurrent();
            Result in = executeCompiledStatement(cs, pvals, cmd.queryTimeout);

            // On the client side, iterate over the vals and throw
            // a BatchUpdateException if a batch status value of
            // esultConstants.EXECUTE_FAILED is encountered in the result
            if (in.isUpdateCount()) {
                if (cs.hasGeneratedColumns()) {
                    RowSetNavigator navgen =
                        in.getChainedResult().getNavigator();

                    while (navgen.next()) {
                        Object[] generatedRow = navgen.getCurrent();

                        generatedResult.getNavigator().add(generatedRow);
                    }
                }

                updateCounts[count++] = in.getUpdateCount();
            } else if (in.isData()) {

                // FIXME:  we don't have what it takes yet
                // to differentiate between things like
                // stored procedure calls to methods with
                // void return type and select statements with
                // a single row/column containing null
                updateCounts[count++] = ResultConstants.SUCCESS_NO_INFO;
            } else if (in.mode == ResultConstants.CALL_RESPONSE) {
                updateCounts[count++] = ResultConstants.SUCCESS_NO_INFO;
            } else if (in.mode == ResultConstants.ERROR) {
                updateCounts = ArrayUtil.arraySlice(updateCounts, 0, count);
                error        = in;

                break;
            } else {
                throw Error.runtimeError(ErrorCode.U_S0500, "Session");
            }
        }

        return Result.newBatchedExecuteResponse(updateCounts, generatedResult,
                error);
    }

    private Result executeDirectBatchStatement(Result cmd) {

        int[] updateCounts;
        int   count;

        count = 0;

        RowSetNavigator nav = cmd.initialiseNavigator();

        updateCounts = new int[nav.getSize()];

        Result error = null;

        while (nav.next()) {
            Result   in;
            Object[] data = nav.getCurrent();
            String   sql  = (String) data[0];

            try {
                Statement cs = compileStatement(sql);

                in = executeCompiledStatement(cs, ValuePool.emptyObjectArray,
                                              cmd.queryTimeout);
            } catch (Throwable t) {
                in = Result.newErrorResult(t);

                // if (t instanceof OutOfMemoryError) {
                // System.gc();
                // }
                // "in" already equals "err"
                // maybe test for OOME and do a gc() ?
                // t.printStackTrace();
            }

            if (in.isUpdateCount()) {
                updateCounts[count++] = in.getUpdateCount();
            } else if (in.isData()) {

                // FIXME:  we don't have what it takes yet
                // to differentiate between things like
                // stored procedure calls to methods with
                // void return type and select statements with
                // a single row/column containing null
                updateCounts[count++] = ResultConstants.SUCCESS_NO_INFO;
            } else if (in.mode == ResultConstants.CALL_RESPONSE) {
                updateCounts[count++] = ResultConstants.SUCCESS_NO_INFO;
            } else if (in.mode == ResultConstants.ERROR) {
                updateCounts = ArrayUtil.arraySlice(updateCounts, 0, count);
                error        = in;

                break;
            } else {
                throw Error.runtimeError(ErrorCode.U_S0500, "Session");
            }
        }

        return Result.newBatchedExecuteResponse(updateCounts, null, error);
    }

    /**
     * Retrieves the result of inserting, updating or deleting a row
     * from an updatable result.
     *
     * @return the result of executing the statement
     */
    private Result executeResultUpdate(Result cmd) {

        long   id         = cmd.getResultId();
        int    actionType = cmd.getActionType();
        Result result     = sessionData.getDataResult(id);

        if (result == null) {
            return Result.newErrorResult(Error.error(ErrorCode.X_24501));
        }

        Object[]       pvals     = (Object[]) cmd.valueData;
        Type[]         types     = cmd.metaData.columnTypes;
        StatementQuery statement = (StatementQuery) result.getStatement();

        sessionContext.rowUpdateStatement.setRowActionProperties(result,
                actionType, statement, types);

        Result resultOut =
            executeCompiledStatement(sessionContext.rowUpdateStatement, pvals,
                                     cmd.queryTimeout);

        return resultOut;
    }

// session DATETIME functions
    long                  currentTimestampSCN = -1;    // initialise to invalid val
    private TimestampData transactionUTC;
    boolean               transactionUTCSet;
    private TimestampData currentDate;
    private TimestampData currentTimestamp;
    private TimestampData localTimestamp;
    private TimeData      currentTime;
    private TimeData      localTime;

    /**
     * Returns the current date/time, unchanged for the duration of the current
     * execution unit (statement).<p>
     *
     * SQL standards require that CURRENT_DATE, CURRENT_TIME,
     * CURRENT_TIMESTAMP, LOCALTIME, and LOCALTIMESTAMP are all evaluated at
     * the same point of time in the duration of each SQL statement, no matter
     * how long the SQL statement takes to complete.<p>
     *
     * When this method or a corresponding method for CURRENT_TIME or
     * CURRENT_TIMESTAMP is first called in the scope of a system change
     * number, currentTimestamp is set to the current system time. All further
     * CURRENT_XXXX and LOCALXXXX calls in this scope will use this base point
     * of time.
     *
     * (fredt@users)
     */
    synchronized TimestampData getCurrentTimestamp() {

        resetCurrentTimestamp();

        return currentTimestamp;
    }

    synchronized TimestampData getLocalTimestamp() {

        resetCurrentTimestamp();

        if (localTimestamp == null) {
            localTimestamp =
                DateTimeType.toLocalTimestampValue(currentTimestamp);
        }

        return localTimestamp;
    }

    public synchronized TimestampData getCurrentDate() {

        resetCurrentTimestamp();

        if (currentDate == null) {
            currentDate = DateTimeType.toCurrentDateValue(currentTimestamp);
        }

        return currentDate;
    }

    /**
     * Returns the current time, unchanged for the duration of the current
     * execution unit (statement)
     */
    synchronized TimeData getCurrentTime() {

        resetCurrentTimestamp();

        if (currentTime == null) {
            currentTime =
                DateTimeType.toCurrentTimeWithZoneValue(currentTimestamp);
        }

        return currentTime;
    }

    synchronized TimeData getLocalTime() {

        resetCurrentTimestamp();

        if (localTime == null) {
            localTime = DateTimeType.toCurrentTimeValue(currentTimestamp);
        }

        return localTime;
    }

    private void resetCurrentTimestamp() {

        if (currentTimestampSCN != actionTimestamp) {
            currentTimestampSCN = actionTimestamp;
            currentTimestamp =
                DateTimeType.getTimestampWithZone(currentTimeZone);
            currentDate    = null;
            localTimestamp = null;
            currentTime    = null;
            localTime      = null;
        }
    }

    TimestampData getTransactionUTC() {

        if (!transactionUTCSet) {
            transactionUTC    = DateTimeType.getSystemTimestampUTC();
            transactionUTCSet = true;
        }

        return transactionUTC;
    }

    // session calendars
    private Calendar calendar;
    private Calendar calendarGMT;

    public int getZoneSeconds() {
        return currentTimestamp.getZone();
    }

    public void resetTimeZone() {

        currentTimeZone = timeZone;

        if (calendar != null) {
            calendar.setTimeZone(currentTimeZone);
        }
    }

    public void setTimeZone(TimeZone zone) {

        currentTimeZone = zone;

        if (calendar != null) {
            calendar.setTimeZone(currentTimeZone);
        }

        resetCurrentTimestamp();
    }

    public Calendar getCalendar() {

        if (calendar == null) {
            calendar = new GregorianCalendar(currentTimeZone);
        }

        return calendar;
    }

    public Calendar getCalendarGMT() {

        if (calendarGMT == null) {
            calendarGMT = new GregorianCalendar(TimeZone.getTimeZone("GMT"));

            calendarGMT.setFirstDayOfWeek(Calendar.MONDAY);
            calendarGMT.setMinimalDaysInFirstWeek(4);
        }

        return calendarGMT;
    }

    public SimpleDateFormat getSimpleDateFormatGMT() {

        if (simpleDateFormatGMT == null) {
            simpleDateFormatGMT = new SimpleDateFormat("MMMM", Locale.ENGLISH);

            simpleDateFormatGMT.setCalendar(getCalendarGMT());
        }

        return simpleDateFormatGMT;
    }

    private Result getAttributesResult(int id) {

        Result   r    = Result.newSessionAttributesResult();
        Object[] data = r.getSingleRowData();

        data[SessionInterface.INFO_ID] = ValuePool.getInt(id);

        switch (id) {

            case SessionInterface.INFO_ISOLATION :
                data[SessionInterface.INFO_INTEGER] =
                    ValuePool.getInt(isolationLevel);
                break;

            case SessionInterface.INFO_AUTOCOMMIT :
                data[SessionInterface.INFO_BOOLEAN] =
                    sessionContext.isAutoCommit;
                break;

            case SessionInterface.INFO_CONNECTION_READONLY :
                data[SessionInterface.INFO_BOOLEAN] =
                    sessionContext.isReadOnly;
                break;

            case SessionInterface.INFO_CATALOG :
                data[SessionInterface.INFO_VARCHAR] =
                    database.getCatalogName().name;
                break;
        }

        return r;
    }

    private Result setAttributes(Result r) {

        Object[] row = r.getSessionAttributes();
        int      id  = ((Integer) row[SessionInterface.INFO_ID]).intValue();

        try {
            switch (id) {

                case SessionInterface.INFO_AUTOCOMMIT : {
                    boolean value =
                        ((Boolean) row[SessionInterface.INFO_BOOLEAN])
                            .booleanValue();

                    this.setAutoCommit(value);

                    break;
                }
                case SessionInterface.INFO_CONNECTION_READONLY : {
                    boolean value =
                        ((Boolean) row[SessionInterface.INFO_BOOLEAN])
                            .booleanValue();

                    this.setReadOnlyDefault(value);

                    break;
                }
                case SessionInterface.INFO_ISOLATION : {
                    int value =
                        ((Integer) row[SessionInterface.INFO_INTEGER])
                            .intValue();

                    this.setIsolationDefault(value);

                    break;
                }
                case SessionInterface.INFO_CATALOG : {
                    String value =
                        ((String) row[SessionInterface.INFO_VARCHAR]);

                    this.setCatalog(value);
                }
            }
        } catch (HsqlException e) {
            return Result.newErrorResult(e);
        }

        return Result.updateZeroResult;
    }

    public synchronized Object getAttribute(int id) {

        switch (id) {

            case SessionInterface.INFO_ISOLATION :
                return ValuePool.getInt(isolationLevel);

            case SessionInterface.INFO_AUTOCOMMIT :
                return sessionContext.isAutoCommit;

            case SessionInterface.INFO_CONNECTION_READONLY :
                return isReadOnlyDefault;

            case SessionInterface.INFO_CATALOG :
                return database.getCatalogName().name;
        }

        return null;
    }

    public synchronized void setAttribute(int id, Object object) {

        switch (id) {

            case SessionInterface.INFO_AUTOCOMMIT : {
                boolean value = ((Boolean) object).booleanValue();

                this.setAutoCommit(value);

                break;
            }
            case SessionInterface.INFO_CONNECTION_READONLY : {
                boolean value = ((Boolean) object).booleanValue();

                this.setReadOnlyDefault(value);

                break;
            }
            case SessionInterface.INFO_ISOLATION : {
                int value = ((Integer) object).intValue();

                this.setIsolationDefault(value);

                break;
            }
            case SessionInterface.INFO_CATALOG : {
                String value = ((String) object);

                this.setCatalog(value);
            }
        }
    }

    // lobs
    public BlobDataID createBlob(long length) {

        long lobID = database.lobManager.createBlob(this, length);

        if (lobID == 0) {
            throw Error.error(ErrorCode.X_0F502);
        }

        return new BlobDataID(lobID);
    }

    public ClobDataID createClob(long length) {

        long lobID = database.lobManager.createClob(this, length);

        if (lobID == 0) {
            throw Error.error(ErrorCode.X_0F502);
        }

        return new ClobDataID(lobID);
    }

    public void registerResultLobs(Result result) {
        sessionData.registerLobForResult(result);
    }

    public Result allocateResultLob(ResultLob result) {
        return sessionData.allocateLobForResult(result);
    }

    Result performLOBOperation(ResultLob cmd) {

        long id        = cmd.getLobID();
        int  operation = cmd.getSubType();

        switch (operation) {

            case ResultLob.LobResultTypes.REQUEST_GET_LOB : {
                return database.lobManager.getLob(id, cmd.getOffset(),
                                                  cmd.getBlockLength());
            }
            case ResultLob.LobResultTypes.REQUEST_GET_LENGTH : {
                return database.lobManager.getLength(id);
            }
            case ResultLob.LobResultTypes.REQUEST_GET_BYTES : {
                return database.lobManager.getBytes(
                    id, cmd.getOffset(), (int) cmd.getBlockLength());
            }
            case ResultLob.LobResultTypes.REQUEST_SET_BYTES : {
                return database.lobManager.setBytes(
                    id, cmd.getOffset(), cmd.getByteArray(),
                    (int) cmd.getBlockLength());
            }
            case ResultLob.LobResultTypes.REQUEST_GET_CHARS : {
                return database.lobManager.getChars(
                    id, cmd.getOffset(), (int) cmd.getBlockLength());
            }
            case ResultLob.LobResultTypes.REQUEST_SET_CHARS : {
                return database.lobManager.setChars(
                    id, cmd.getOffset(), cmd.getCharArray(),
                    (int) cmd.getBlockLength());
            }
            case ResultLob.LobResultTypes.REQUEST_TRUNCATE : {
                return database.lobManager.truncate(id, cmd.getOffset());
            }
            case ResultLob.LobResultTypes.REQUEST_DUPLICATE_LOB : {
                return database.lobManager.createDuplicateLob(id);
            }
            case ResultLob.LobResultTypes.REQUEST_CREATE_BYTES :
            case ResultLob.LobResultTypes.REQUEST_CREATE_CHARS :
            case ResultLob.LobResultTypes.REQUEST_GET_BYTE_PATTERN_POSITION :
            case ResultLob.LobResultTypes.REQUEST_GET_CHAR_PATTERN_POSITION : {
                throw Error.error(ErrorCode.X_0A501);
            }
            default : {
                throw Error.runtimeError(ErrorCode.U_S0500, "Session");
            }
        }
    }

    // DatabaseMetaData.getURL should work as specified for
    // internal connections too.
    public String getInternalConnectionURL() {
        return DatabaseURL.S_URL_PREFIX + database.getURI();
    }

    public Result cancel(Result result) {

        if (result.getType() == ResultConstants.SQLCANCEL) {
            if (result.getSessionRandomID() == randomId) {
                database.txManager.resetSession(
                    this, this, statementStartTimestamp,
                    TransactionManager.resetSessionStatement);
            }
        }

        return Result.updateZeroResult;
    }

    public boolean isProcessingScript() {
        return isProcessingScript;
    }

    public boolean isProcessingLog() {
        return isProcessingLog;
    }

    // schema object methods
    public void setSchema(String schema) {
        currentSchema = database.schemaManager.getSchemaHsqlName(schema);
    }

    public void setCatalog(String catalog) {

        if (database.getCatalogName().name.equals(catalog)) {
            return;
        }

        throw Error.error(ErrorCode.X_3D000);
    }

    /**
     * If schemaName is null, return the current schema name, else return
     * the HsqlName object for the schema. If schemaName does not exist,
     * throw.
     */
    HsqlName getSchemaHsqlName(String name) {
        return name == null ? currentSchema
                            : database.schemaManager.getSchemaHsqlName(name);
    }

    /**
     * Same as above, but return string
     */
    public String getSchemaName(String name) {
        return name == null ? currentSchema.name
                            : database.schemaManager.getSchemaName(name);
    }

    public void setCurrentSchemaHsqlName(HsqlName name) {
        currentSchema = name;
    }

    public HsqlName getCurrentSchemaHsqlName() {
        return currentSchema;
    }

    public int getResultMemoryRowCount() {
        return resultMaxMemoryRows;
    }

    public void setResultMemoryRowCount(int count) {

        if (database.logger.getTempDirectoryPath() != null) {
            if (count < 0) {
                count = 0;
            }

            resultMaxMemoryRows = count;
        }
    }

    // warnings
    HsqlDeque sqlWarnings;

    public void addWarning(HsqlException warning) {

        if (sqlWarnings == null) {
            sqlWarnings = new HsqlDeque();
        }

        if (sqlWarnings.size() > 9) {
            sqlWarnings.removeFirst();
        }

        int index = sqlWarnings.indexOf(warning);

        if (index >= 0) {
            sqlWarnings.remove(index);
        }

        sqlWarnings.add(warning);
    }

    public HsqlException[] getAndClearWarnings() {

        if (sqlWarnings == null) {
            return HsqlException.emptyArray;
        }

        HsqlException[] array = new HsqlException[sqlWarnings.size()];

        sqlWarnings.toArray(array);
        sqlWarnings.clear();

        return array;
    }

    public HsqlException getLastWarning() {

        if (sqlWarnings == null || sqlWarnings.size() == 0) {
            return null;
        }

        return (HsqlException) sqlWarnings.getLast();
    }

    public void clearWarnings() {

        if (sqlWarnings != null) {
            sqlWarnings.clear();
        }
    }

    // services
    TypedComparator  typedComparator = new TypedComparator(this);
    Scanner          secondaryScanner;
    SimpleDateFormat simpleDateFormat;
    SimpleDateFormat simpleDateFormatGMT;
    Random           randomGenerator = new Random();
    long             seed            = -1;
    public final int randomId = randomGenerator.nextInt(Integer.MAX_VALUE);

    //
    public TypedComparator getComparator() {
        return typedComparator;
    }

    public double random(long seed) {

        if (this.seed != seed) {
            randomGenerator.setSeed(seed);

            this.seed = seed;
        }

        return randomGenerator.nextDouble();
    }

    public double random() {
        return randomGenerator.nextDouble();
    }

    public Scanner getScanner() {

        if (secondaryScanner == null) {
            secondaryScanner = new Scanner();
        }

        return secondaryScanner;
    }

    // properties
    HsqlProperties clientProperties;

    public HsqlProperties getClientProperties() {

        if (clientProperties == null) {
            clientProperties = new HsqlProperties();

            clientProperties.setProperty(
                HsqlDatabaseProperties.jdbc_translate_tti_types,
                database.sqlTranslateTTI);
            clientProperties.setProperty(
                HsqlDatabaseProperties.sql_live_object,
                database.sqlLiveObject);
        }

        return clientProperties;
    }

    // logging and SEQUENCE current values
    void logSequences() {

        HashMap map = sessionData.sequenceUpdateMap;

        if (map == null || map.isEmpty()) {
            return;
        }

        Iterator it = map.keySet().iterator();

        for (int i = 0, size = map.size(); i < size; i++) {
            NumberSequence sequence = (NumberSequence) it.next();

            database.logger.writeSequenceStatement(this, sequence);
        }

        sessionData.sequenceUpdateMap.clear();
    }

    String getStartTransactionSQL() {

        StringBuilder sb = new StringBuilder();

        sb.append(Tokens.T_START).append(' ').append(Tokens.T_TRANSACTION);

        if (isolationLevel != isolationLevelDefault) {
            sb.append(' ');
            appendIsolationSQL(sb, isolationLevel);
        }

        return sb.toString();
    }

    String getTransactionIsolationSQL() {

        StringBuilder sb = new StringBuilder();

        sb.append(Tokens.T_SET).append(' ').append(Tokens.T_TRANSACTION);
        sb.append(' ');
        appendIsolationSQL(sb, isolationLevel);

        return sb.toString();
    }

    String getSessionIsolationSQL() {

        StringBuilder sb = new StringBuilder();

        sb.append(Tokens.T_SET).append(' ').append(Tokens.T_SESSION);
        sb.append(' ').append(Tokens.T_CHARACTERISTICS).append(' ');
        sb.append(Tokens.T_AS).append(' ').append(Tokens.T_TRANSACTION).append(
            ' ');
        appendIsolationSQL(sb, isolationLevelDefault);

        return sb.toString();
    }

    static void appendIsolationSQL(StringBuilder sb, int isolationLevel) {

        sb.append(Tokens.T_ISOLATION).append(' ');
        sb.append(Tokens.T_LEVEL).append(' ');
        sb.append(getIsolationString(isolationLevel));
    }

    static String getIsolationString(int isolationLevel) {

        switch (isolationLevel) {

            case SessionInterface.TX_READ_UNCOMMITTED :
            case SessionInterface.TX_READ_COMMITTED :
                StringBuilder sb = new StringBuilder();

                sb.append(Tokens.T_READ).append(' ');
                sb.append(Tokens.T_COMMITTED);

                return sb.toString();

            case SessionInterface.TX_REPEATABLE_READ :
            case SessionInterface.TX_SERIALIZABLE :
            default :
                return Tokens.T_SERIALIZABLE;
        }
    }

    String getSetSchemaStatement() {
        return "SET SCHEMA " + currentSchema.statementName;
    }

    // timeouts
    class TimeoutManager {

        volatile int  currentTimeout;
        volatile long checkTimestampSCN;

        void startTimeout(int timeout) {

            if (timeout == 0) {
                return;
            }

            boolean add = checkTimestampSCN == 0;

            currentTimeout    = timeout + 1;
            checkTimestampSCN = statementStartTimestamp;

            if (add) {
                database.timeoutRunner.addSession(this);
            }
        }

        void endTimeout() {
            currentTimeout = 0;
        }

        public boolean checkTimeout() {

            if (currentTimeout == 0) {
                return false;
            }

            if (checkTimestampSCN != statementStartTimestamp) {
                return false;
            }

            currentTimeout--;

            if (currentTimeout <= 0) {
                currentTimeout = 0;

                database.txManager.resetSession(
                    Session.this, Session.this, checkTimestampSCN,
                    TransactionManager.resetSessionStatement);

                return true;
            }

            return false;
        }

        public boolean isClosed() {
            return Session.this.isClosed;
        }
    }
}
