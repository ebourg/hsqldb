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


package org.hsqldb.cmdline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.hsqldb.cmdline.sqltool.Token;
import org.hsqldb.cmdline.libclone.FrameworkLogger;
import org.hsqldb.cmdline.utilclone.RCData;

/* $Id$ */

/**
 * A command-line JDBC SQL tool supporting both interactive and
 * non-interactive usage.
 * <P>
 * See JavaDocs for the main method for syntax of how to run from the
 * command-line.
 * <P>
 * Programmatic users will usually want to use the objectMain(String[]) method
 * if they want arguments and behavior exactly like command-line SqlTool.
 * But in many cases, you will have better control and efficiency by using
 * the SqlFile class directly.  The file
 * <CODE>src/org/hsqldb/sample/SqlFileEmbedder.java</CODE>
 * in the HSQLDB distribution provides an example for this latter strategy.
 *
 * @see <a href="../../../../util-guide/sqltool-chapt.html" target="guide">
 *     The SqlTool chapter of the
 *     HyperSQL Utilities Guide</a>
 * @see #main(String[])
 * @see #objectMain(String[])
 * @see SqlFile
 * @see org.hsqldb.sample.SqlFileEmbedder
 * @version $Revision$, $Date$
 * @author Blaine Simpson (blaine dot simpson at admc dot com)
 */
public class SqlTool {
    private static final FrameworkLogger logger =
            FrameworkLogger.getLog(SqlTool.class);
    public static final String DEFAULT_RCFILE =
        System.getProperty("user.home") + "/sqltool.rc";
    // N.b. the following are static!
    private static final String revString = "$Revision$";
    private static final int revStringLength = revString.length();
    @SuppressWarnings("ConstantConditions")
    private static final String  revnum =
            (revStringLength - " $".length() > "$Revision: ".length())
            ?  revString.substring("$Revision: ".length(),
                    revStringLength - " $".length())
            : "<UNTRACKED>";

    public static final int SQLTOOLERR_EXITVAL = 1;
    public static final int SYNTAXERR_EXITVAL = 11;
    public static final int RCERR_EXITVAL = 2;
    public static final int SQLERR_EXITVAL = 3;
    public static final int IOERR_EXITVAL = 4;
    public static final int FILEERR_EXITVAL = 5;
    public static final int INPUTERR_EXITVAL = 6;
    public static final int CONNECTERR_EXITVAL = 7;

    /**
     * The configuration identifier to use when connection parameters are
     * specified on the command line
     */
    private static final String CMDLINE_ID = "cmdline";

    /** Platform-specific line separator */
    public static final String LS = System.getProperty("line.separator");

    /** Utility nested class for internal use. */
    private static class BadCmdline extends Exception {
        static final long serialVersionUID = -2134764796788108325L;
        BadCmdline() {
            // Purposefully empty
        }
    }

    /** Utility object for internal use. */
    private static final BadCmdline bcl = new BadCmdline();

    /** For trapping of exceptions inside this class.
     * These are always handled inside this class.
     */
    private static class PrivateException extends Exception {
        static final long serialVersionUID = -7765061479594523462L;

        /* Unused at this time
        PrivateException() {
            super();
        }
        */

        PrivateException(String s) {
            super(s);
        }
    }

    public static class SqlToolException extends Exception {
        static final long serialVersionUID = 1424909871915188519L;
        int exitValue;

        SqlToolException(String message, int exitValue) {
            super(message);
            this.exitValue = exitValue;
        }
        SqlToolException(int exitValue, String message) {
            this(message, exitValue);
        }
        SqlToolException(int exitValue) {
            super();
            this.exitValue = exitValue;
        }
    }

    /**
     * Prompt the user for a password.
     *
     * @param username The user the password is for
     * @return The password the user entered
     */
    private static String promptForPassword(final String username)
    throws PrivateException {

        BufferedReader console;
        String         password;

        try {
            console = new BufferedReader(new InputStreamReader(System.in));

            // Prompt for password
            System.out.print(SqltoolRB.passwordFor_prompt.getString(
                    RCData.expandSysPropVars(username)));

            // Read the password from the command line
            password = console.readLine();

            if (password == null) {
                password = "";
            } else {
                password = password.trim();
            }
        } catch (IOException e) {
            throw new PrivateException(e.getMessage());
        } finally {
            console = null;  // Encourage GC of buffers
        }

        return password;
    }

    /**
     * Parses a comma delimited string of name value pairs into a
     * <code>Map</code> object.
     *
     * @param inVarString The string to parse
     * @param varMap The map to save the paired values into
     * @param lowerCaseKeys Set to <code>true</code> if the map keys should be
     *        converted to lower case
     */
    private static void varParser(final String inVarString,
                                  final Map<String, String> varMap,
                                  final boolean lowerCaseKeys)
                                  throws PrivateException {

        int       equals;
        String    var;
        String    val;

        if (varMap == null) {
            throw new IllegalArgumentException(
                    "varMap is null in SqlTool.varParser call");
        }
        if (inVarString == null) {
            throw new IllegalArgumentException(
                    "inVarString is null in SqlTool.varParser call");
        }
        boolean escapesPresent = inVarString.contains("\\,");
        String  varString = escapesPresent ?
                inVarString.replace("\\,", "\u0002")
                : inVarString;

        for (String token : varString.split("\\s*,\\s*")) {
            equals = token.indexOf('=');

            if (equals < 1) {
                throw new PrivateException(
                    SqltoolRB.SqlTool_varset_badformat.getString());
            }

            var = token.substring(0, equals).trim();
            val = token.substring(equals + 1).trim();
            if (escapesPresent) {
                val = val.replace("\u0002", ",");
            }

            if (var.length() < 1) {
                throw new PrivateException(
                    SqltoolRB.SqlTool_varset_badformat.getString());
            }

            if (lowerCaseKeys) {
                var = var.toLowerCase();
            }

            varMap.put(var, val);
        }
    }

    /**
     * A static wrapper for objectMain, so that that method may be executed
     * as a Java "program".
     * <P>
     * Throws only RuntimeExceptions or Errors, because this method is intended
     * to System.exit() for all but disastrous system problems, for which
     * the inconvenience of a stack trace would be the least of your worries.
     * <P>
     * If you don't want SqlTool to System.exit(), then use the method
     * objectMain() instead of this method.
     *
     * @param args arguments
     * @see #objectMain(String[])
     */
    public static void main(final String[] args) {
        try {
            SqlTool.objectMain(args);
        } catch (SqlToolException fr) {
            System.err.println(
                    (fr.getMessage() == null) ? fr : fr.getMessage());
            System.exit(fr.exitValue);
        }
        System.exit(0);
    }

    /**
     * Connect to a JDBC Database and execute the commands given on
     * stdin or in SQL file(s).
     * <P>
     * This method is changed for HSQLDB 1.8.0.8 and later to never
     * System.exit().
     * Developers may catch Throwables to handle all fatal situations.
     *
     * @param arg  Run "java... org.hsqldb.cmdline.SqlTool --help" for syntax.
     * @throws SqlToolException  Upon any fatal error, with useful
     *                          reason as the exception's message.
     */
    public static void objectMain(final String[] arg) throws SqlToolException {
        logger.finer("Invoking SqlTool");

        /*
         * The big picture is, we parse input args; load a RCData;
         * get a JDBC Connection with the RCData; instantiate and
         * execute as many SqlFiles as we need to.
         */
        String  rcFile           = null;
        PipedReader  tmpReader   = null;
        String  sqlText          = null;
        String  driver           = null;
        String  targetDb         = null;
        boolean debug            = false;
        URL[]   scriptFiles      = null;
        int     i                = -1;
        boolean listMode         = false;
        boolean interactive;
        boolean noinput          = false;
        boolean noautoFile       = false;
        boolean autoCommit       = false;
        boolean rcParamsOverride = false;
        Boolean coeOverride      = null;
        Boolean stdinputOverride = null;
        String  rcParams         = null;
        String  rcUrl;
        String  rcUsername;
        String  rcUser;
        String  rcPassword;
        String  rcCharset;
        String  rcTruststore;
        String  rcTransIso;
        String  rcDriver;
        Map<String, String> rcFields;
        String  parameter;
        SqlFile[] sqlFiles;
        Connection conn          = null;
        Map<String, String> userVars = new HashMap<>();

        try { // Try block to GC tmpReader
        try { // Try block for BadCmdline
            while ((i + 1 < arg.length)) if (arg[i + 1].startsWith("--")) {
                i++;

                if (arg[i].length() == 2) {
                    break;             // "--"
                }

                parameter = arg[i].substring(2).toLowerCase();

                if (parameter.equals("help")) {
                    System.out.println(SqltoolRB.SqlTool_syntax.getString(
                            revnum, RCData.DEFAULT_JDBC_DRIVER));
                    return;
                }
                if (parameter.equals("abortonerr")) {
                    if (coeOverride != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_abort_continue_mutuallyexclusive.getString());
                    }

                    coeOverride = Boolean.FALSE;
                } else if (parameter.equals("continueonerr")) {
                    if (coeOverride != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_abort_continue_mutuallyexclusive.getString());
                    }

                    coeOverride = Boolean.TRUE;
                } else if (parameter.startsWith("continueonerr=")) {
                    if (coeOverride != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_abort_continue_mutuallyexclusive.getString());
                    }

                    coeOverride = Boolean.valueOf(
                            arg[i].substring("--continueonerr=".length()));
                } else if (parameter.equals("list")) {
                    if (listMode) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    listMode = true;
                } else if (parameter.equals("rcfile")) {
                    if (++i == arg.length) {
                        throw bcl;
                    }
                    if (rcFile != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }

                    rcFile = arg[i];
                } else if (parameter.startsWith("rcfile=")) {
                    if (rcFile != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    rcFile = arg[i].substring("--rcfile=".length());
                } else if (parameter.equals("setvar")) {
                    if (++i == arg.length) {
                        throw bcl;
                    }

                    try {
                        varParser(arg[i], userVars, false);
                    } catch (PrivateException pe) {
                        throw new SqlToolException(
                                RCERR_EXITVAL, pe.getMessage());
                    }
                } else if (parameter.startsWith("setvar=")) {
                    try {
                        varParser(arg[i].substring("--setvar=".length()),
                                userVars, false);
                    } catch (PrivateException pe) {
                        throw new SqlToolException(
                                RCERR_EXITVAL, pe.getMessage());
                    }
                } else if (parameter.equals("sql")) {
                    noinput = true;    // but turn back on if file "-" specd.

                    if (++i == arg.length) {
                        throw bcl;
                    }
                    if (sqlText != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }

                    sqlText = arg[i];
                } else if (parameter.startsWith("sql=")) {
                    noinput = true;    // but turn back on if file "-" specd.
                    if (sqlText != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    sqlText = arg[i].substring("--sql=".length());
                } else if (parameter.equals("debug")) {
                    if (debug) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    debug = true;
                } else if (parameter.equals("noautofile")) {
                    if (noautoFile) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    noautoFile = true;
                } else if (parameter.equals("autocommit")) {
                    if (autoCommit) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    autoCommit = true;
                } else if (parameter.equals("stdinput")) {
                    noinput          = false;
                    if (stdinputOverride != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    stdinputOverride = Boolean.TRUE;
                } else if (parameter.equals("noinput")) {
                    noinput          = true;
                    if (stdinputOverride != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    stdinputOverride = Boolean.FALSE;
                } else if (parameter.equals("driver")) {
                    if (++i == arg.length) {
                        throw bcl;
                    }
                    if (driver != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }

                    driver = arg[i];
                } else if (parameter.startsWith("driver=")) {
                    if (driver != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    driver = arg[i].substring("--driver=".length());
                } else if (parameter.equals("inlinerc")) {
                    if (++i == arg.length) {
                        throw bcl;
                    }
                    if (rcParams != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }

                    rcParams = arg[i];
                } else if (parameter.startsWith("inlinerc=")) {
                    if (rcParams != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    rcParams = arg[i].substring("--inlinerc=".length());
                } else if (parameter.equals("overriderc")) {
                    if (++i == arg.length) {
                        throw bcl;
                    }
                    if (rcParams != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }

                    rcParamsOverride = true;
                    rcParams = arg[i];
                } else if (parameter.startsWith("overriderc=")) {
                    if (rcParams != null) {
                        throw new SqlToolException(SYNTAXERR_EXITVAL,
                                SqltoolRB.SqlTool_params_redundant.getString());
                    }
                    rcParamsOverride = true;
                    rcParams = arg[i].substring("--overriderc=".length());
                } else {
                    throw bcl;
                }
            } else if (arg[i + 1].startsWith("-P")
                    || arg[i + 1].startsWith("-p")) {
                i++;
                boolean sepSwitch = arg[i].length() < 3;
                if (sepSwitch) {
                    if (++i == arg.length) {
                        throw bcl;
                    }
                }

                int equalAt = arg[i].indexOf('=');
                if (equalAt < (sepSwitch ? 1 : 3)) {
                    throw new SqlToolException(RCERR_EXITVAL,
                            "Specified var assignment contains no '='");
                }
                userVars.put(arg[i].substring(sepSwitch ? 0 : 2, equalAt),
                        arg[i].substring(equalAt + 1));
            } else {
                break;
            }

            if (!listMode && (rcParams == null || rcParamsOverride)
              && ++i != arg.length) {
                // If an inline RC file was specified, don't look for targetDb
                targetDb = arg[i];
                if (targetDb.equals("-")) targetDb = null;
            }

            int scriptIndex = 0;

            if (sqlText != null) {
                try {
                    tmpReader = new PipedReader();
                    PipedWriter tmpWriter = new PipedWriter(tmpReader);
                    // My best guess is that the encoding here will be however
                    // we read the SQL text from the command-line, which will
                    // be the platform default encoding.  Therefore, don't
                    // specify an encoding for this pipe.
                    try {
                        tmpWriter.write(sqlText + LS);
                        tmpWriter.flush();
                    } finally {
                        try {
                            tmpWriter.close();
                        } finally {
                            tmpWriter = null;  // Encourage GC of buffers
                        }
                    }
                } catch (IOException ioe) {
                    throw new SqlToolException(IOERR_EXITVAL,
                            SqltoolRB.sqltempfile_fail.getString(
                            ioe.toString()));
                }
            }

            if (stdinputOverride != null) {
                noinput = !stdinputOverride;
            }

            interactive = (!noinput) && (arg.length <= i + 1);

            if (arg.length == i + 2 && arg[i + 1].equals("-")) {
                if (stdinputOverride == null) {
                    noinput = false;
                }
            } else if (arg.length > i + 1) {

                // I.e., if there are any SQL files specified.
                scriptFiles = new URL[arg.length - i - 1
                        + ((stdinputOverride == null
                                || !stdinputOverride) ? 0 : 1)];

                if (debug) {
                    System.err.println("scriptFiles has "
                                       + scriptFiles.length + " elements");
                }

                while (i + 1 < arg.length) try {
                    scriptFiles[scriptIndex] =
                      SqlFile.URL_WITH_PROTO_RE.matcher(arg[++i]).matches() ?
                        new URL(arg[i]) :
                        new URL("file", null, arg[i]);
                } catch (MalformedURLException mue) {
                    // TODO: Define a new ResourceBundle message!
                    throw new RuntimeException(
                      "Invalid SQL file URL " + arg[i]);
                }

                if (stdinputOverride != null && stdinputOverride) {
                    noinput                    = true;
                }
            }
        } catch (BadCmdline bcle) {
            throw new SqlToolException(SYNTAXERR_EXITVAL,
                    SqltoolRB.SqlTool_syntax.getString(
                    revnum, RCData.DEFAULT_JDBC_DRIVER));
        }

        RCData conData = null;

        if (listMode || targetDb != null) {
            try {
                conData = new RCData(new File((rcFile == null)
                                              ? DEFAULT_RCFILE
                                              : rcFile), targetDb);
            } catch (RuntimeException re) {
                throw re;  // Unrecoverable
            } catch (Exception e) {
                throw new SqlToolException(RCERR_EXITVAL,
                        SqltoolRB.conndata_retrieval_fail.getString(
                        targetDb, e.getMessage()));
            }
        }
        // Use the inline RC file if it was specified
        if (rcParams != null) {
            if (rcParamsOverride && conData == null)
                throw new RuntimeException(
                  "rcParams override but no urlid specified");
            if (!rcParamsOverride && conData != null)
                throw new RuntimeException(
                  "inlineRc override but urlid was specified");
            rcFields = new HashMap<>();

            try {
                varParser(rcParams, rcFields, true);
            } catch (PrivateException e) {
                throw new SqlToolException(SYNTAXERR_EXITVAL, e.getMessage());
            }

            rcUrl        = rcFields.remove("url");
            rcUser       = rcFields.remove("user");  // Legacy
            rcUsername   = rcFields.remove("username");
            rcCharset    = rcFields.remove("charset");
            rcTruststore = rcFields.remove("truststore");
            rcPassword   = rcFields.remove("password");
            rcTransIso   = rcFields.remove("transiso");
            rcDriver     = rcFields.remove("driver");
            if (rcFields.size() > 0) {
                throw new SqlToolException(INPUTERR_EXITVAL,
                        SqltoolRB.rcdata_inline_extravars.getString(
                        rcFields.keySet().toString()));
            }
            if (rcUser != null) {
                if (rcUsername != null)
                    throw new RuntimeException("RC params 'user' and "
                     + "'username' both set.  Set only 'username'");
                rcUsername = rcUser;
            }

            if (rcDriver != null && driver != null)
                throw new SqlToolException(RCERR_EXITVAL,
                        SqltoolRB.rcdata_driver_conflict.getString());
            if (rcPassword != null && rcPassword.length() > 0)
                throw new SqlToolException(RCERR_EXITVAL,
                        SqltoolRB.rcdata_password_visible.getString());
            if (conData == null) try {
                conData = new RCData(CMDLINE_ID, rcUrl, rcUsername, rcPassword,
                  (rcDriver == null ? driver : rcDriver), rcCharset,
                  rcTruststore, null, rcTransIso);
            } catch (RuntimeException re) {
                throw re;  // Unrecoverable
            } catch (Exception e) {
                throw new SqlToolException(RCERR_EXITVAL,
                        SqltoolRB.rcdata_genfromvalues_fail.getString(
                        e.getMessage()));
            } else {
                // Override conData field values
                if (rcUrl != null) conData.url = rcUrl;
                if (rcUsername != null) conData.username = rcUsername;
                if (rcCharset != null) conData.charset = rcCharset;
                if (rcTruststore != null) conData.truststore = rcTruststore;
                if (rcPassword != null) conData.password = rcPassword;
                if (rcTransIso != null) conData.ti = rcTransIso;
                if (rcDriver != null) conData.driver = rcDriver;
            }

            // Don't ask for password if what we have already is invalid!
            if (conData.url == null || conData.url.length() < 1)
                throw new SqlToolException(RCERR_EXITVAL,
                        SqltoolRB.rcdata_inlineurl_missing.getString());

            // We now allow both null and "" user name, but we require password
            // if the user name != null.
            if (conData.username != null && conData.password == null) try {
                conData.password   = promptForPassword(conData.username);
            } catch (PrivateException e) {
                throw new SqlToolException(INPUTERR_EXITVAL,
                        SqltoolRB.password_readfail.getString(e.getMessage()));
            }
        }

        //if (debug) {
            //conData.report();
        //}

        if (listMode) {
            // listMode has been handled above.
            // Just returning here to prevent unexpected consequences if the
            // user specifies both an inline RC (will be ignored) and
            // --list.
            return;
        }

        if (interactive) System.out.print("SqlTool v. " + revnum + '.' + LS);

        if (conData != null) try {
            conn = conData.getConnection(
                null, System.getProperty("javax.net.ssl.trustStore"));

            conn.setAutoCommit(autoCommit);

            String conBanner;
            if (interactive && (conBanner = SqlFile.getBanner(conn)) != null) {
                System.out.println(conBanner);
            }
        } catch (RuntimeException re) {
            throw re;  // Unrecoverable
        } catch (Exception e) {
            if (debug) logger.error(e.getClass().getName(), e);

            // Let's not continue as if nothing is wrong.
            String reportUser = (conData.username == null)
                    ? "<DFLTUSER>" : conData.username;
            throw new SqlToolException(CONNECTERR_EXITVAL,
                    SqltoolRB.connection_fail.getString(conData.url,
                    reportUser, e.getMessage()));
        }

        URL[] emptyUrlArray      = {};
        URL[] singleNullUrlArray = { null };
        File   autoFile            = null;

        if (interactive && !noautoFile) {
            autoFile = new File(System.getProperty("user.home")
                                + "/auto.sql");

            if ((!autoFile.isFile()) || !autoFile.canRead()) {
                autoFile = null;
            }
        }

        if (scriptFiles == null) {

            // I.e., if no SQL files given on command-line.
            // Input file list is either nothing or {null} to read stdin.
            scriptFiles = (noinput ? emptyUrlArray
                                   : singleNullUrlArray);
        }

        int numFiles = scriptFiles.length;

        if (tmpReader != null) {
            numFiles += 1;
        }

        if (autoFile != null) {
            numFiles += 1;
        }

        sqlFiles = new SqlFile[numFiles];

        // We print version before execing this one.
        int interactiveFileIndex = -1;
        String encoding = (conData == null) ? null : conData.charset;

        try {
            int fileIndex = 0;

            if (autoFile != null) {
                sqlFiles[fileIndex++] = new SqlFile(
                  new URL("file", null, autoFile.getPath()), encoding);
            }

            if (tmpReader != null) {
                sqlFiles[fileIndex++] = new SqlFile(tmpReader,
                  "--sql", System.out, null, false, (URL) null);
            }

            for (URL scriptFile : scriptFiles) {
                if (interactiveFileIndex < 0 && interactive) {
                    interactiveFileIndex = fileIndex;
                }

                sqlFiles[fileIndex++] = (scriptFile == null)
                        ?  (new SqlFile(encoding, interactive))
                        :  (new SqlFile(scriptFile,encoding, interactive));
            }
        } catch (IOException ioe) {
            try {
                if (conn != null) conn.close();
            } catch (Exception e) {
                // Can only report on so many errors at one time
            }

            throw new SqlToolException(FILEERR_EXITVAL, ioe.getMessage());
        }
        } finally {
            // DO NOT close tmpReader, since SqlFile needs to read from it
            // (and will auto-close it).
            tmpReader = null;  // Encourage GC of buffers
        }

        Map<String, Token> macros = null;
        try {
            for (SqlFile sqlFile : sqlFiles) {
                if (conn != null) sqlFile.setConnection(conn);
                if (userVars.size() > 0) sqlFile.addUserVars(userVars);
                if (macros != null) sqlFile.addMacros(macros);
                if (coeOverride != null)
                    sqlFile.setContinueOnError(coeOverride);

                sqlFile.execute();
                userVars = sqlFile.getUserVars();
                macros = sqlFile.getMacros();
                conn = sqlFile.getConnection();
            }
            // Following two Exception types are handled properly inside of
            // SqlFile.  We just need to return an appropriate error status.
        } catch (SqlToolError ste) {
            throw new SqlToolException(SQLTOOLERR_EXITVAL);
        } catch (SQLException se) {
            // SqlTool will only throw an SQLException if it is in
            // "\c false" mode.
            throw new SqlToolException(SQLERR_EXITVAL);
        } finally {
            try {
                if (conn != null) conn.close();
            } catch (Exception e) {
                // Purposefully doing nothing
            }
        }
    }
}
