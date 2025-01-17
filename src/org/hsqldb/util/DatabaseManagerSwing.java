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


package org.hsqldb.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

// dmarshall@users - 20020101 - original swing port of DatabaseManager
// sqlbob@users 20020401 - patch 537501 by ulrivo - commandline arguments
// sqlbob@users 20020407 - patch 1.7.0 - reengineering and enhancements
// nickferguson@users 20021005 - patch 1.7.1 - enhancements
// deccles@users 2004 - 2008 - bug fixes and enhancements
// weconsultants@users 20041109 - version 1.8.0 - reengineering and enhancements:
//      Added: Goodies 'Look and Feel'.
//      Added: a Font Changer(Font Type\Style).
//      Added: a Color Changer (foreground\background).
//      Added: RowCounts for each JTree table nodes.
//      Added: OneTouchExpandable attribute to JSplitPanes.
//      Moved: setFramePosition code to a CommonSwing.setFramePositon() Method.
//      Added: call to new method to handle exception processing (CommonSwing.errorMessage());
//      Added: Added a new pane added at the bottom of the Frame. (Status Icon and StatusLine).
//      Added: 2 Methods (setStatusMessage()), one overrides the other. One to change the running status
//             another to allow a message to be posted without changing the Status Icon if needed.
//      Added: Added a customCursor for the current wait cursor
//      Added: Ability to switch the current LAF while running (Native,Java or Motif)
// unsaved@users 2005xxxx - improvements and bug fixes
// fredt@users - version 2.5.0 - removed deprecated
// fredt@users - version 2.5.1 - enhancements

/**
 * Swing Tool for managing a JDBC database.
 * <pre>
 * {@code
 *             Usage: java DatabaseManagerSwing [--options]
 *             where options include:
 *              --driver <classname>  jdbc driver class
 *              --url <name>          jdbc url
 *              --user <name>         username used for connection
 *              --password <password> password for this user
 *              --dir <path>          default directory
 *              --script <file>       reads from script file
 *              --urlid <urlid>       get connection info from RC file
 *              --rcfile <file>       use instead of default (with urlid)
 *              --noexit              Don't exit JVM
 * }
 * </pre>
 *
 * Note that the sys-table switch will not work for Oracle, because Oracle
 * does not categorize their system tables correctly in the JDBC Metadata.
 *
 * @author dmarshall@users
 * @author Bob Preston (sqlbob@users dot sourceforge.net)
 * @version 2.6.0
 * @since 1.7.0
 */
public class DatabaseManagerSwing extends JFrame
implements ActionListener, WindowListener, KeyListener, MouseListener {

    /*
     * This is down here because it is an  implementation note, not a
     * Javadoc comment!
     * Tue Apr 26 16:38:54 EDT 2005
     * Switched default switch method from "-switch" to "--switch" because
     * "-switch" usage is ambiguous as used here.  Single switches should
     * be reserved for single-letter switches which can be mixed like
     * "-u -r -l" = "-url".  -blaine
     */
    private static String homedir;
    private boolean       isOracle = false;    // Need some workarounds for Oracle

    static {
        homedir = System.getProperty("user.home");
    }

    ArrayList<JMenuItem>        localActionList = new ArrayList<JMenuItem>();
    private JFrame              jframe;
    private static final String DEFAULT_RCFILE  = homedir + "/dbmanager.rc";
    private static boolean      TT_AVAILABLE    = false;

    static {
        try {
            Class.forName(DatabaseManagerSwing.class.getPackage().getName()
                          + ".Transfer");

            TT_AVAILABLE = true;
        } catch (Throwable t) {

            //System.err.println("Failed to get "
            //+ DatabaseManagerSwing.class.getPackage().getName()
            //+ ".Transfer: " + t);
            // Enable this print statement for debugging class access problems.
        }
    }

    private static final String HELP_TEXT =
        "See the HSQLDB Utilities Guide, forums and mailing lists \n"
        + "at http://hsqldb.org.\n\n"
        + "Please paste the following version identifier with any\n"
        + "problem reports or help requests:  $Revision$"
        + (TT_AVAILABLE ? ""
                        : ("\n\nTransferTool classes are not in CLASSPATH.\n"
                           + "To enable the Tools menu, add 'transfer.jar' "
                           + "to your class path."));
    private static final String ABOUT_TEXT =
        "$Revision$ of DatabaseManagerSwing\n\n"
        + "Copyright (c) 2001-2021, The HSQL Development Group.\n"
        + "http://hsqldb.org  (Utilities Guide available at this site).\n\n\n"
        + "You may use and redistribute according to the HSQLDB\n"
        + "license documented in the source code and at the web\n"
        + "site above."
        + (TT_AVAILABLE ? "\n\nTransferTool options are available."
                        : "");
    static final String    NL         = System.getProperty("line.separator");
    static final String    NULL_STR   = "[null]";
    static int             iMaxRecent = 24;
    Connection             cConn;
    Connection             rowConn;    // holds the connection for getting table row counts
    DatabaseMetaData       dMeta;
    Statement              sStatement;
    JMenu                  mRecent;
    String[]               sRecent;
    int                    iRecent;
    JTextArea              txtCommand;
    JScrollPane            txtCommandScroll;
    JTree                  tTree;
    JScrollPane            tScrollPane;
    DefaultTreeModel       treeModel;
    TableModel             tableModel;
    DefaultMutableTreeNode rootNode;
    JPanel                 pResult;
    long                   lTime;
    GridSwing              gResult;

    /**
     * I think this is used to store model info whether we're using Grid
     *  output or not (this object is queried for data to display for
     *  text output mode).
     *  If so, the presentation-independent model part should be moved
     *  to an appropriately-named class instead of storing pure data in
     *  a Swing-specific class.
     */
    JTable            gResultTable;
    JScrollPane       gScrollPane;
    JTextArea         txtResult;
    JScrollPane       txtResultScroll;
    JSplitPane        nsSplitPane;     // Contains query over results
    JSplitPane        ewSplitPane;     // Contains tree beside nsSplitPane
    boolean           bHelp;
    RootPaneContainer fMain;
    static boolean    bMustExit;

    /** Value of this variable only retained if huge input script read in. */
    String          sqlScriptBuffer = null;
    private boolean showSchemas  = true;
    private boolean showTooltips = true;
    private boolean autoRefresh  = true;
    private boolean gridFormat   = true;

    boolean                     displayRowCounts = false;
    boolean                     showSys          = false;
    boolean                     showIndexDetails = true;
    String                      currentLAF       = null;
    JPanel                      pStatus;
    static JButton              iReadyStatus;
    JRadioButtonMenuItem        rbAllSchemas = new JRadioButtonMenuItem("*");
    JMenuItem                   mitemAbout   = new JMenuItem("About", 'A');
    JMenuItem                   mitemHelp    = new JMenuItem("Help", 'H');
    JMenuItem mitemUpdateSchemas             = new JMenuItem("Update Schemas");
    JCheckBoxMenuItem boxAutoCommit =
        new JCheckBoxMenuItem(AUTOCOMMIT_BOX_TEXT);
    JCheckBoxMenuItem boxLogging = new JCheckBoxMenuItem(LOGGING_BOX_TEXT);
    JCheckBoxMenuItem boxShowSchemas =
        new JCheckBoxMenuItem(SHOWSCHEMAS_BOX_TEXT);
    JCheckBoxMenuItem boxAutoRefresh =
        new JCheckBoxMenuItem(AUTOREFRESH_BOX_TEXT);
    JCheckBoxMenuItem boxTooltips  = new JCheckBoxMenuItem(SHOWTIPS_BOX_TEXT);
    JCheckBoxMenuItem boxRowCounts = new JCheckBoxMenuItem(ROWCOUNTS_BOX_TEXT);
    JCheckBoxMenuItem boxShowGrid  = new JCheckBoxMenuItem(GRID_BOX_TEXT);
    JCheckBoxMenuItem boxShowSys   = new JCheckBoxMenuItem(SHOWSYS_BOX_TEXT);

    // Consider adding GTK and Plaf L&Fs.
    JRadioButtonMenuItem rbNativeLF =
        new JRadioButtonMenuItem("Native Look & Feel");
    JRadioButtonMenuItem rbJavaLF =
        new JRadioButtonMenuItem("Java Look & Feel");
    JRadioButtonMenuItem rbMotifLF =
        new JRadioButtonMenuItem("Motif Look & Feel");
    JLabel                      jStatusLine;
    static String               READY_STATUS         = "Ready";
    private static final String AUTOCOMMIT_BOX_TEXT  = "Autocommit mode";
    private static final String LOGGING_BOX_TEXT     = "Logging mode";
    private static final String SHOWSCHEMAS_BOX_TEXT = "Show schemas";
    private static final String AUTOREFRESH_BOX_TEXT = "Auto-refresh tree";
    private static final String SHOWTIPS_BOX_TEXT    = "Show Tooltips";
    private static final String ROWCOUNTS_BOX_TEXT   = "Show row counts";
    private static final String SHOWSYS_BOX_TEXT     = "Show system tables";
    private static final String GRID_BOX_TEXT =
        "Show results in Grid (a.o.t. Text)";

    // variables to hold the default cursors for these top level swing objects
    // so we can restore them when we exit our thread
    Cursor        fMainCursor;
    Cursor        txtCommandCursor;
    Cursor        txtResultCursor;
    HashMap<AbstractButton,String> tipMap = new HashMap<AbstractButton,String>();
    private final JMenu mnuSchemas = new JMenu("Schemas");

    /**
     * Wait Cursor
     */

    // Changed: (weconsultants@users): commonted out the, out of the box, cursor to use a custom cursor
    private final Cursor waitCursor = new Cursor(Cursor.WAIT_CURSOR);

    // (ulrivo): variables set by arguments from the commandline

    static String  defDirectory;
    private String schemaFilter = null;

    public DatabaseManagerSwing() {
        jframe = new JFrame("HyperSQL Database Manager");
        fMain  = jframe;
    }

    public DatabaseManagerSwing(JFrame frameIn) {
        jframe = frameIn;
        fMain  = jframe;
    }

    /**
     * Run with --help switch for usage instructions.
     *
     * @param arg arguments
     * @throws IllegalArgumentException for the obvious reason
     */
    public static void main(String[] arg) {

        System.getProperties().put("sun.java2d.noddraw", "true");

        // (ulrivo): read all arguments from the command line
        String currentArg;
        String lowerArg;
        String urlid = null;
        String rcFile = null;
        String defDriver = "org.hsqldb.jdbc.JDBCDriver";
        String defURL = "jdbc:hsqldb:mem:.";
        String defUser = "SA";
        String defPassword = "";
        boolean autoConnect = false;
        boolean urlidConnect = false;

        bMustExit = true;

        for (int i = 0; i < arg.length; i++) {
            currentArg = arg[i];
            lowerArg   = arg[i].toLowerCase();

            if (lowerArg.startsWith("--")) {
                lowerArg = lowerArg.substring(1);
            }

            if (lowerArg.equals("-noexit") || lowerArg.equals("-help")) {

                //
            } else if (i == arg.length - 1) {
                throw new IllegalArgumentException("No value for argument "
                                                   + currentArg);
            }

            i++;

            if (lowerArg.equals("-driver")) {
                defDriver   = arg[i];
                autoConnect = true;
            } else if (lowerArg.equals("-url")) {
                defURL      = arg[i];
                autoConnect = true;
            } else if (lowerArg.equals("-user")) {
                defUser     = arg[i];
                autoConnect = true;
            } else if (lowerArg.equals("-password")) {
                defPassword = arg[i];
                autoConnect = true;
            } else if (lowerArg.equals("-urlid")) {
                urlid        = arg[i];
                urlidConnect = true;
            } else if (lowerArg.equals("-rcfile")) {
                rcFile       = arg[i];
                urlidConnect = true;
            } else if (lowerArg.equals("-dir")) {
                defDirectory = arg[i];
            } else if (lowerArg.equals("-script")) {
                // dropped script processing
            } else if (lowerArg.equals("-noexit")) {
                bMustExit = false;

                i--;
            } else if (lowerArg.equals("-help")) {
                showUsage();

                return;
            } else {
                /* Syntax ERRORS should either throw or exit with non-0 status.
                 * In our case, it may be unsafe to exit, so we throw.
                 * (I.e. should provide easy way for caller to programmatically
                 * determine that there was an invocation problem).
                 */
                throw new IllegalArgumentException(
                    "invalid argument " + currentArg + " try:  java... "
                    + DatabaseManagerSwing.class.getName() + " --help");

                // No reason to localize, since the main syntax message is
                // not localized.
            }
        }

        DatabaseManagerSwing m = new DatabaseManagerSwing();


        m.main();

        Connection c = null;

        m.setWaiting("Initializing");

        try {
            if (autoConnect && urlidConnect) {
                throw new IllegalArgumentException(
                    "You may not specify both (urlid) AND (url/user/password).");
            }

            if (autoConnect) {
                c = ConnectionDialogSwing.createConnection(defDriver, defURL,
                        defUser, defPassword);
            } else if (urlidConnect) {
                if (urlid == null) {
                    throw new IllegalArgumentException(
                        "You must specify an 'urlid' to use an RC file");
                }

                String rcfilepath = (rcFile == null) ? DEFAULT_RCFILE
                                                     : rcFile;
                RCData rcdata     = new RCData(new File(rcfilepath), urlid);

                c = rcdata.getConnection(
                    null, System.getProperty("javax.net.ssl.trustStore"));
            } else {
                c = ConnectionDialogSwing.createConnection(m.jframe, "Connect");
            }
        } catch (Exception e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        } finally {
            m.setWaiting(null);
        }

        if (c != null) {
            m.connect(c);
        }

        m.start();
    }

    /**
     * This stuff is all quick, except for the refreshTree(). This unit can be
     * kicked off in main Gui thread. The refreshTree will be backgrounded and
     * this method will return.
     *
     * @param c Connection
     */
    public void connect(Connection c) {

        schemaFilter = null;

        if (c == null) {
            return;
        }

        if (cConn != null) {
            try {
                cConn.close();
            } catch (SQLException e) {

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }
        }

        cConn = c;

        // Added: (weconsultants@users) Need to barrow to get the table rowcounts
        rowConn = c;

        try {
            dMeta      = cConn.getMetaData();
            isOracle = (dMeta.getDatabaseProductName().contains("Oracle"));
            sStatement = cConn.createStatement();

            updateAutoCommitBox();

            // Workaround for EXTREME SLOWNESS getting this info from O.
            showIndexDetails = !isOracle;

            Driver driver = DriverManager.getDriver(dMeta.getURL());
            ConnectionSetting newSetting = new ConnectionSetting(
                dMeta.getDatabaseProductName(), driver.getClass().getName(),
                dMeta.getURL(),
                dMeta.getUserName().replaceAll("@localhost", ""), "");
            Hashtable settings =
                ConnectionDialogCommon.loadRecentConnectionSettings();

            ConnectionDialogCommon.addToRecentConnectionSettings(settings,
                    newSetting);
            ConnectionDialogSwing.setConnectionSetting(newSetting);
            refreshTree();
            clearResultPanel();
        } catch (SQLException e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        } catch (IOException e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        } catch (Exception e) {
            CommonSwing.errorMessage(e);
        }
    }

    private static void showUsage() {

        System.out.println(
            "Usage: java DatabaseManagerSwing [--options]\n"
            + "where options include:\n"
            + "    --help                show this message\n"
            + "    --driver <classname>  jdbc driver class\n"
            + "    --url <name>          jdbc url\n"
            + "    --user <name>         username used for connection\n"
            + "    --password <password> password for this user\n"
            + "    --urlid <urlid>       use url/user/password/driver in rc file\n"
            + "    --rcfile <file>       (defaults to 'dbmanager.rc' in home dir)\n"
            + "    --dir <path>          default directory\n"
            + "    --script <file>       reads from script file\n"
            + "    --noexit              do not call system.exit()");
    }

    private void insertTestData() {

        try {
            DatabaseManagerCommon.createTestTables(sStatement);
            txtCommand.setText(
                DatabaseManagerCommon.createTestData(sStatement));

            for (int i = 0; i < DatabaseManagerCommon.testDataSql.length;
                    i++) {
                addToRecent(DatabaseManagerCommon.testDataSql[i]);
            }

            executeCurrentSQL();
        } catch (SQLException e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        }
    }

    private DBMPrefs prefs = null;

    public void main() {

        JMenu     jmenu;
        JMenuItem mitem;

        try {
            prefs = new DBMPrefs(false);
        } catch (Exception e) {
/*
            System.err.println(
                "Failed to load preferences.  Proceeding with defaults:\n");
*/
        }

        if (prefs == null) {
            setLF(CommonSwing.Native);
        } else {
            autoRefresh      = prefs.autoRefresh;
            displayRowCounts = prefs.showRowCounts;
            showSys          = prefs.showSysTables;
            showSchemas      = prefs.showSchemas;
            gridFormat       = prefs.resultGrid;
            showTooltips     = prefs.showTooltips;

            setLF(prefs.laf);
        }

        // (ulrivo): An actual icon.  N.b., this adds some tips to the tip map
        fMain.getContentPane().add(createToolBar(), "North");

        if (fMain instanceof java.awt.Frame) {
            ((java.awt.Frame) fMain).setIconImage(
                CommonSwing.getIcon("Frame"));
        }

        if (fMain instanceof java.awt.Window) {
            ((java.awt.Window) fMain).addWindowListener(this);
        }

        JMenuBar bar = new JMenuBar();

        // used shortcuts: CERGTSIUDOLM
        String[] fitems = {
            "-Connect...", "-Close Connection", "--", "OOpen Script...", "-Save Script...",
            "-Save Result...", "--", "-Exit"
        };

        jmenu = addMenu(bar, "File", fitems);

        // All actions after Connect and the divider are local.
        for (int i = 3; i < jmenu.getItemCount(); i++) {
            mitem = jmenu.getItem(i);

            if (mitem != null) {
                localActionList.add(mitem);
            }
        }

        Object[] vitems = {
            "RRefresh Tree", boxAutoRefresh, "--", boxRowCounts, boxShowSys,
            boxShowSchemas, boxShowGrid
        };

        addMenu(bar, "View", vitems);

        String[] sitems = {
            "SSELECT", "IINSERT", "UUPDATE", "DDELETE", "EEXECUTE", "---",
            "-CREATE TABLE", "-DROP TABLE", "-CREATE INDEX", "-DROP INDEX",
            "--", "CCOMMIT*", "LROLLBACK*", "-CHECKPOINT*", "-SCRIPT", "-SET",
            "-SHUTDOWN", "--", "-Test Script"
        };

        addMenu(bar, "Command", sitems);

        mRecent = new JMenu("Recent");

        mRecent.setMnemonic(KeyEvent.VK_R);
        bar.add(mRecent);

        ButtonGroup lfGroup = new ButtonGroup();

        lfGroup.add(rbNativeLF);
        lfGroup.add(rbJavaLF);
        lfGroup.add(rbMotifLF);
        boxShowSchemas.setSelected(showSchemas);
        boxShowGrid.setSelected(gridFormat);
        boxTooltips.setSelected(showTooltips);
        boxShowGrid.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G,
                InputEvent.CTRL_DOWN_MASK));
        boxAutoRefresh.setSelected(autoRefresh);
        boxRowCounts.setSelected(displayRowCounts);
        boxShowSys.setSelected(showSys);
        rbNativeLF.setActionCommand("LFMODE:" + CommonSwing.Native);
        rbJavaLF.setActionCommand("LFMODE:" + CommonSwing.Java);
        rbMotifLF.setActionCommand("LFMODE:" + CommonSwing.Motif);
        tipMap.put(mitemUpdateSchemas, "Refresh the schema list in this menu");
        tipMap.put(rbAllSchemas, "Display items in all schemas");
        tipMap.put(mitemAbout, "Display product information");
        tipMap.put(mitemHelp, "Display advice for obtaining help");
        tipMap.put(boxAutoRefresh,
                   "Refresh tree (and schema list) automatically"
                   + "when YOU modify database objects");
        tipMap.put(boxShowSchemas,
                   "Display object names in tree-like schemaname.basename");
        tipMap.put(rbNativeLF,
                   "Set Look and Feel to Native for your platform");
        tipMap.put(rbJavaLF, "Set Look and Feel to Java");
        tipMap.put(rbMotifLF, "Set Look and Feel to Motif");
        boxTooltips.setToolTipText("Display tooltips (hover text), like this");
        tipMap.put(boxAutoCommit,
                   "Shows current Auto-commit mode.  Click to change");
        tipMap.put(
            boxLogging,
            "Shows current JDBC DriverManager logging mode.  Click to change");
        tipMap.put(boxShowSys, "Show system tables in table tree to the left");
        tipMap.put(boxShowGrid, "Show query results in grid (in text if off)");
        tipMap.put(boxRowCounts, "Show row counts with table names in tree");
        boxAutoRefresh.setMnemonic(KeyEvent.VK_C);
        boxShowSchemas.setMnemonic(KeyEvent.VK_Y);
        boxAutoCommit.setMnemonic(KeyEvent.VK_A);
        boxShowSys.setMnemonic(KeyEvent.VK_Y);
        boxShowGrid.setMnemonic(KeyEvent.VK_G);
        boxRowCounts.setMnemonic(KeyEvent.VK_C);
        boxLogging.setMnemonic(KeyEvent.VK_L);
        rbAllSchemas.setMnemonic(KeyEvent.VK_ASTERISK);
        rbNativeLF.setMnemonic(KeyEvent.VK_N);
        rbJavaLF.setMnemonic(KeyEvent.VK_J);
        rbMotifLF.setMnemonic(KeyEvent.VK_M);
        mitemUpdateSchemas.setMnemonic(KeyEvent.VK_U);

        Object[] soptions = {

            // Added: (weconsultants@users) New menu options
            rbNativeLF, rbJavaLF, rbMotifLF, "--", "-Set Fonts", "--",
            boxAutoCommit, "--", "-Disable MaxRows", "-Set MaxRows to 100",
            "--", boxLogging, "--", "-Insert test data"
        };

        addMenu(bar, "Options", soptions);

        String[] stools = {
            "-Dump", "-Restore", "-Transfer"
        };

        jmenu = addMenu(bar, "Tools", stools);

        jmenu.setEnabled(TT_AVAILABLE);
        localActionList.add(jmenu);

        for (int i = 0; i < jmenu.getItemCount(); i++) {
            mitem = jmenu.getItem(i);

            if (mitem != null) {
                localActionList.add(mitem);
            }
        }

        mnuSchemas.setMnemonic(KeyEvent.VK_S);
        bar.add(mnuSchemas);

        JMenu mnuHelp = new JMenu("Help");

        mnuHelp.setMnemonic(KeyEvent.VK_H);
        mnuHelp.add(mitemAbout);
        mnuHelp.add(mitemHelp);
        mnuHelp.add(boxTooltips);
        rbAllSchemas.addActionListener(schemaListListener);

        // May be illegal:
        mitemUpdateSchemas.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {
                updateSchemaList();
            }
        });
        mitemHelp.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {

                JOptionPane.showMessageDialog(fMain.getContentPane(),
                                              HELP_TEXT, "HELP",
                                              JOptionPane.INFORMATION_MESSAGE);
            }
        });
        mitemAbout.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {

                JOptionPane.showMessageDialog(fMain.getContentPane(),
                                              ABOUT_TEXT, "About",
                                              JOptionPane.INFORMATION_MESSAGE);
            }
        });
        boxTooltips.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {

                showTooltips = boxTooltips.isSelected();

                resetTooltips();
            }
        });
        bar.add(mnuHelp);

        if (fMain instanceof JFrame) {
            ((JFrame) fMain).setJMenuBar(bar);
        }

        initGUI();

        sRecent = new String[iMaxRecent];

        // Modified: (weconsultants@users)Mode code to CommonSwing for general use
        CommonSwing.setFramePositon((JFrame) fMain);

        // Modified: (weconsultants@users) Changed from deprecated show()
        ((Component) fMain).setVisible(true);


        // Added: (weconsultants@users): For preloadng FontDialogSwing
        FontDialogSwing.creatFontDialog(this);

        // This must be done AFTER all tip texts are put into the map
        resetTooltips();
        txtCommand.requestFocus();
    }

    private JMenu addMenu(JMenuBar b, String name, Object[] items) {

        JMenu menu = new JMenu(name);

        menu.setMnemonic(name.charAt(0));
        addMenuItems(menu, items);
        b.add(menu);

        return menu;
    }

    private void addMenuItems(JMenu f, Object[] m) {

        /*
         * This method needs to be completely written or just
         * obliterated and we'll use the Menu objects directly.
         * Problem is, passing in Strings for menu elements makes it
         * extremely difficult to use non-text menu items (an important
         * part of a good Gui), hot-keys, mnemonic keys, tooltips.
         * Note the "trick" required here to set hot-keys.
         */
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();

        for (int i = 0; i < m.length; i++) {
            if (m[i].equals("--")) {
                f.addSeparator();
            } else if (m[i].equals("---")) {

                // (ulrivo): full size on screen with less than 640 width
                if (d.width >= 640) {
                    f.addSeparator();
                } else {
                    return;
                }
            } else {
                JMenuItem item;

                if (m[i] instanceof JMenuItem) {
                    item = (JMenuItem) m[i];
                } else if (m[i] instanceof String) {
                    item = new JMenuItem(((String) m[i]).substring(1));

                    char c = ((String) m[i]).charAt(0);

                    if (c != '-') {
                        KeyStroke key =
                            KeyStroke.getKeyStroke(c, InputEvent.CTRL_DOWN_MASK);

                        item.setAccelerator(key);
                    }
                } else {
                    throw new RuntimeException(
                        "Unexpected element for menu item creation: "
                        + m[i].getClass().getName());
                }

                item.addActionListener(this);
                f.add(item);
            }
        }
    }

    public void keyPressed(KeyEvent k) {}

    public void keyReleased(KeyEvent k) {}

    public void keyTyped(KeyEvent k) {

        if (k.getKeyChar() == '\n' && k.isControlDown()) {
            k.consume();
            executeCurrentSQL();
        }
    }

    public void actionPerformed(ActionEvent ev) {

        String s = ev.getActionCommand();

        if (s == null) {
            if (ev.getSource() instanceof JMenuItem) {
                s = ((JMenuItem) ev.getSource()).getText();
            }
        }

        if (s == null) {}
        else if (s.equals("Exit")) {
            windowClosing(null);
        } else if (s.equals("Transfer")) {
            Transfer.work(null);
        } else if (s.equals("Dump")) {
            Transfer.work(new String[]{ "-d" });
        } else if (s.equals("Restore")) {
            JOptionPane.showMessageDialog(
                fMain.getContentPane(),
                "Use Ctrl-R or the View menu to\n"
                + "update nav. tree after Restoration", "Suggestion",
                    JOptionPane.INFORMATION_MESSAGE);

            // Regardless of whether autoRefresh is on, half of
            // Restore runs asynchronously, so we could only
            // update the tree from within the Transfer class.
            Transfer.work(new String[]{ "-r" });

            // Would be better to put the modal suggestion here, after the
            // user selects the import file, but that messes up the z
            // layering of the 3 windows already displayed.
        } else if (s.equals(LOGGING_BOX_TEXT)) {
            setLogToSystem(boxLogging.isSelected());
        } else if (s.equals(AUTOREFRESH_BOX_TEXT)) {
            autoRefresh = boxAutoRefresh.isSelected();

            refreshTree();
        } else if (s.equals("Refresh Tree")) {
            refreshTree();
        } else if (s.startsWith("#")) {
            int i = Integer.parseInt(s.substring(1));

            txtCommand.setText(sRecent[i]);
        } else if (s.equals("Connect...")) {
            Connection newCon;

            try {
                setWaiting("Connecting");

                newCon = ConnectionDialogSwing.createConnection(jframe,
                        "Connect");
            } finally {
                setWaiting(null);
            }

            connect(newCon);
        } else if (s.equals("Close Connection")) {
            if (cConn != null) {
                try {
                    cConn.close();
                } catch (SQLException e) {

                    //  Added: (weconsultants@users)
                    CommonSwing.errorMessage(e);
                }

                cConn = null;
                dMeta = null;

                rootNode.setUserObject("Connection");

                directRefreshTree();
            }
        } else if (s.equals(GRID_BOX_TEXT)) {
            gridFormat = boxShowGrid.isSelected();

            displayResults();
        } else if (s.equals("Open Script...")) {
            JFileChooser f = new JFileChooser(".");

            f.setDialogTitle("Open Script...");

            // (ulrivo): set default directory if set from command line
            if (defDirectory != null) {
                f.setCurrentDirectory(new File(defDirectory));
            }

            int option = f.showOpenDialog((Component) fMain);

            if (option == JFileChooser.APPROVE_OPTION) {
                File file = f.getSelectedFile();

                if (file != null) {
                    sqlScriptBuffer =
                        DatabaseManagerCommon.readFile(file.getAbsolutePath());

                    if (4096 <= sqlScriptBuffer.length()) {
                        int eoThirdLine = sqlScriptBuffer.indexOf('\n');

                        if (eoThirdLine > 0) {
                            eoThirdLine = sqlScriptBuffer.indexOf('\n',
                                                                  eoThirdLine
                                                                  + 1);
                        }

                        if (eoThirdLine > 0) {
                            eoThirdLine = sqlScriptBuffer.indexOf('\n',
                                                                  eoThirdLine
                                                                  + 1);
                        }

                        if (eoThirdLine < 1) {
                            eoThirdLine = 100;
                        }

                        txtCommand.setText(
                            "............... Script File loaded: " + file
                            + " ..................... \n"
                            + "............... Click Execute or Clear "
                            + "...................\n"
                            + sqlScriptBuffer.substring(0, eoThirdLine + 1)
                            + "........................................."
                            + "................................\n"
                            + "..........................................."
                            + "..............................\n");
                        txtCommand.setEnabled(false);
                    } else {
                        txtCommand.setText(sqlScriptBuffer);

                        sqlScriptBuffer = null;

                        txtCommand.setEnabled(true);
                    }
                }
            }
        } else if (s.equals("Save Script...")) {
            JFileChooser f = new JFileChooser(".");

            f.setDialogTitle("Save Script");

            // (ulrivo): set default directory if set from command line
            if (defDirectory != null) {
                f.setCurrentDirectory(new File(defDirectory));
            }

            int option = f.showSaveDialog((Component) fMain);

            if (option == JFileChooser.APPROVE_OPTION) {
                File file = f.getSelectedFile();

                if (file != null) {
                    DatabaseManagerCommon.writeFile(file.getAbsolutePath(),
                                                    txtCommand.getText());
                }
            }
        } else if (s.equals("Save Result...")) {
            JFileChooser f = new JFileChooser(".");

            f.setDialogTitle("Save Result...");

            // (ulrivo): set default directory if set from command line
            if (defDirectory != null) {
                f.setCurrentDirectory(new File(defDirectory));
            }

            int option = f.showSaveDialog((Component) fMain);

            if (option == JFileChooser.APPROVE_OPTION) {
                File file = f.getSelectedFile();

                if (file != null) {
                    showResultInText();
                    DatabaseManagerCommon.writeFile(file.getAbsolutePath(),
                                                    txtResult.getText());
                }
            }
        } else if (s.equals(SHOWSYS_BOX_TEXT)) {
            showSys = boxShowSys.isSelected();

            refreshTree();
        } else if (s.equals(ROWCOUNTS_BOX_TEXT)) {
            displayRowCounts = boxRowCounts.isSelected();

            refreshTree();
        } else if (s.startsWith("LFMODE:")) {
            setLF(s.substring("LFMODE:".length()));
        } else if (s.equals("Set Fonts")) {

            // Added: (weconsultants@users)
            FontDialogSwing.creatFontDialog(this);
        } else if (s.equals(AUTOCOMMIT_BOX_TEXT)) {
            try {
                cConn.setAutoCommit(boxAutoCommit.isSelected());
            } catch (SQLException e) {
                boxAutoCommit.setSelected(!boxAutoCommit.isSelected());

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("COMMIT*")) {
            try {
                cConn.commit();
                showHelp(new String[] {
                    "", "COMMIT executed"
                });
            } catch (SQLException e) {

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("Insert test data")) {
            insertTestData();
            refreshTree();
        } else if (s.equals("ROLLBACK*")) {
            try {
                cConn.rollback();
                showHelp(new String[] {
                    "", "ROLLBACK executed"
                });
            } catch (SQLException e) {

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("Disable MaxRows")) {
            try {
                sStatement.setMaxRows(0);
            } catch (SQLException e) {

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("Set MaxRows to 100")) {
            try {
                sStatement.setMaxRows(100);
            } catch (SQLException e) {
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("SELECT")) {
            showHelp(DatabaseManagerCommon.selectHelp);
        } else if (s.equals("INSERT")) {
            showHelp(DatabaseManagerCommon.insertHelp);
        } else if (s.equals("UPDATE")) {
            showHelp(DatabaseManagerCommon.updateHelp);
        } else if (s.equals("DELETE")) {
            showHelp(DatabaseManagerCommon.deleteHelp);
        } else if (s.equals("EXECUTE")) {
            executeCurrentSQL();
        } else if (s.equals("CREATE TABLE")) {
            showHelp(DatabaseManagerCommon.createTableHelp);
        } else if (s.equals("DROP TABLE")) {
            showHelp(DatabaseManagerCommon.dropTableHelp);
        } else if (s.equals("CREATE INDEX")) {
            showHelp(DatabaseManagerCommon.createIndexHelp);
        } else if (s.equals("DROP INDEX")) {
            showHelp(DatabaseManagerCommon.dropIndexHelp);
        } else if (s.equals("CHECKPOINT*")) {
            try {
                cConn.createStatement().executeUpdate("CHECKPOINT");
                showHelp(new String[] {
                    "", "CHECKPOINT executed"
                });
            } catch (SQLException e) {
                CommonSwing.errorMessage(e);
            }
        } else if (s.equals("SCRIPT")) {
            showHelp(DatabaseManagerCommon.scriptHelp);
        } else if (s.equals("SHUTDOWN")) {
            showHelp(DatabaseManagerCommon.shutdownHelp);
        } else if (s.equals("SET")) {
            showHelp(DatabaseManagerCommon.setHelp);
        } else if (s.equals("Test Script")) {
            showHelp(DatabaseManagerCommon.testHelp);
        } else if (s.equals(SHOWSCHEMAS_BOX_TEXT)) {
            showSchemas = boxShowSchemas.isSelected();

            refreshTree();
        } else {
            throw new RuntimeException("Unexpected action triggered: " + s);
        }
    }

    private void displayResults() {

        if (gridFormat) {
            setResultsInGrid();
        } else {
            setResultsInText();
        }
    }

    private void setResultsInGrid() {

        pResult.removeAll();
        pResult.add(gScrollPane, BorderLayout.CENTER);
        pResult.doLayout();
        gResult.fireTableChanged(null);
        pResult.repaint();
    }

    private void setResultsInText() {

        pResult.removeAll();
        pResult.add(txtResultScroll, BorderLayout.CENTER);
        pResult.doLayout();
        showResultInText();
        pResult.repaint();
    }

    private void showHelp(String[] help) {

        txtCommand.setText(help[0]);

        bHelp = true;

        pResult.removeAll();
        pResult.add(txtResultScroll, BorderLayout.CENTER);
        pResult.doLayout();
        txtResult.setText(help[1]);
        pResult.repaint();
        txtCommand.requestFocus();
        txtCommand.setCaretPosition(help[0].length());
    }

    public void windowActivated(WindowEvent e) {}

    public void windowDeactivated(WindowEvent e) {}

    public void windowClosed(WindowEvent e) {}

    public void windowDeiconified(WindowEvent e) {}

    public void windowIconified(WindowEvent e) {}

    public void windowOpened(WindowEvent e) {}

    public void windowClosing(WindowEvent ev) {

        stop();

        try {
            if (cConn != null) {
                cConn.close();
            }

            if (prefs != null) {
                prefs.autoRefresh   = autoRefresh;
                prefs.showRowCounts = displayRowCounts;
                prefs.showSysTables = showSys;
                prefs.showSchemas   = showSchemas;
                prefs.resultGrid    = gridFormat;
                prefs.showTooltips  = showTooltips;
                prefs.laf           = currentLAF;

                prefs.store();
            }
        } catch (Exception e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        }

        if (fMain instanceof java.awt.Window) {
            ((java.awt.Window) fMain).dispose();
        }

        if (bMustExit) {
            System.exit(0);
        }
    }

    private void clear() {

        sqlScriptBuffer = null;

        txtCommand.setText("");
        txtCommand.setEnabled(true);
    }

    private String busyText = null;

    private void backgroundIt(Runnable r, String description) {

        if (busyText != null) {
            Toolkit.getDefaultToolkit().beep();

            return;
        }

        // set Waiting mode here.  Inverse op must be called by final()
        // in the Thread.run() of every background thread.
        setWaiting(description);
        SwingUtilities.invokeLater(r);
    }

    private void clearResultPanel() {

        gResult.setHead(new Object[0]);
        gResult.clear();

        if (gridFormat) {
            gResult.fireTableChanged(null);
        } else {
            showResultInText();
        }
    }

    public void setWaiting(String description) {

        busyText = description;

        if (busyText == null) {

            // restore the cursors we saved
            if (fMain instanceof java.awt.Frame) {
                ((java.awt.Frame) fMain).setCursor(fMainCursor);
            } else {
                ((Component) fMain).setCursor(fMainCursor);
            }

            txtCommand.setCursor(txtCommandCursor);
            txtResult.setCursor(txtResultCursor);

            /* @todo: Enable actionButtons */
        } else {

            // save the old cursors
            if (fMainCursor == null) {
                fMainCursor = ((fMain instanceof java.awt.Frame)
                               ? (((java.awt.Frame) fMain).getCursor())
                               : (((Component) fMain).getCursor()));
                txtCommandCursor = txtCommand.getCursor();
                txtResultCursor  = txtResult.getCursor();
            }

            // set the cursors to the wait cursor
            if (fMain instanceof java.awt.Frame) {
                ((java.awt.Frame) fMain).setCursor(waitCursor);
            } else {
                ((Component) fMain).setCursor(waitCursor);
            }

            txtCommand.setCursor(waitCursor);
            txtResult.setCursor(waitCursor);

            /* @todo: Disable actionButtons */
        }

        setStatusLine(busyText, ((busyText == null) ? gResult.getRowCount()
                                                    : 0));
    }

    private final Runnable enableButtonRunnable = new Runnable() {

        public void run() {
            jbuttonClear.setEnabled(true);
            jbuttonExecute.setEnabled(true);
        }
    };
    private final Runnable disableButtonRunnable = new Runnable() {

        public void run() {
            jbuttonClear.setEnabled(false);
            jbuttonExecute.setEnabled(false);
        }
    };
    private Thread           buttonUpdaterThread = null;
    private static final int BUTTON_CHECK_PERIOD = 500;
    private final Runnable         buttonUpdater       = new Runnable() {

        public void run() {

            boolean havesql;

            while (true) {
                try {
                    Thread.sleep(BUTTON_CHECK_PERIOD);
                } catch (InterruptedException ie) {}

                if (buttonUpdaterThread == null) {    // Pointer to me
                    return;
                }

                havesql = (txtCommand.getText().length() > 0);

                if (jbuttonClear.isEnabled() != havesql) {
                    SwingUtilities.invokeLater(havesql ? enableButtonRunnable
                                                       : disableButtonRunnable);
                }
            }
        }
    };
    private JButton jbuttonClear;
    private JButton jbuttonExecute;

    public void start() {

        if (buttonUpdaterThread == null) {
            buttonUpdaterThread = new Thread(buttonUpdater);
        }

        buttonUpdaterThread.start();
    }

    public void stop() {

        System.err.println("Stopping");

        Thread t = buttonUpdaterThread;

        if (t != null) {
            t.setContextClassLoader(null);
        }

        buttonUpdaterThread = null;
    }

    private final Runnable treeRefreshRunnable = new Runnable() {

        public void run() {

            try {
                directRefreshTree();
            } catch (RuntimeException re) {
                CommonSwing.errorMessage(re);

                throw re;
            } finally {
                setWaiting(null);
            }
        }
    };

    /**
     * Schedules to run in a Gui-safe thread
     */
    protected void executeCurrentSQL() {

        if (txtCommand.getText().length() < 1) {
            CommonSwing.errorMessage("No SQL to execute");

            return;
        }

        backgroundIt(new StatementExecRunnable(), "Executing SQL");
    }

    protected class StatementExecRunnable implements Runnable {

        public void run() {

            gResult.clear();

            try {
                if (txtCommand.getText().startsWith("-->>>TEST<<<--")) {
                    testPerformance();
                } else {
                    executeSQL();
                }

                updateResult();
                displayResults();
                updateAutoCommitBox();

                // System.gc();
            } catch (RuntimeException re) {
                CommonSwing.errorMessage(re);

                throw re;
            } finally {
                setWaiting(null);
            }
        }
    }

    private void executeSQL() {

        String[] g   = new String[1];
        String   sql;

        try {
            lTime = System.nanoTime();
            sql   = ((sqlScriptBuffer == null ? txtCommand.getText()
                                              : sqlScriptBuffer));

            if (sStatement == null) {
                g[0] = "no connection";

                gResult.setHead(g);

                return;
            }

            sStatement.execute(sql);

            lTime = System.nanoTime() - lTime;

            int r = sStatement.getUpdateCount();

            if (r == -1) {
                ResultSet rs = sStatement.getResultSet();

                try {
                    formatResultSet(rs);
                } catch (Throwable t) {
                    g[0] = "Error displaying the ResultSet";

                    gResult.setHead(g);

                    String s = t.getMessage();

                    g[0] = s;

                    gResult.addRow(g);
                }
            } else if (sStatement.getMoreResults()) {    // repeated for if a procedure returns a result set
                ResultSet rs = sStatement.getResultSet();

                try {
                    formatResultSet(rs);
                } catch (Throwable t) {
                    g[0] = "Error displaying the ResultSet";

                    gResult.setHead(g);

                    String s = t.getMessage();

                    g[0] = s;

                    gResult.addRow(g);
                }
            } else {
                g[0] = "update count";

                gResult.setHead(g);

                g[0] = "" + r;

                gResult.addRow(g);
            }

            if (sqlScriptBuffer == null) {
                addToRecent(sql);
                txtCommand.setEnabled(true);    // clear() does this otherwise
            } else {
                clear();
            }
        } catch (SQLException e) {
            lTime = System.nanoTime() - lTime;
            g[0]  = "SQL Error";

            gResult.setHead(g);

            String s = e.getMessage();

            s    += " / Error Code: " + e.getErrorCode();
            s    += " / State: " + e.getSQLState();
            g[0] = s;

            gResult.addRow(g);

            //  Added: (weconsultants@users)
            // CommonSwing.errorMessage(e);
            return;
        }

        if (autoRefresh) {

            // We're already running in a "busy" thread.  Just update the
            // status text.
            setStatusLine("Refreshing object tree", 0);

            String upper = sql.toUpperCase(Locale.ENGLISH);

            // This test can be very liberal.  Too liberal will just do
            // some extra refreshes.  Too conservative will display
            // obsolete info.
            if (upper.contains("ALTER") || upper.contains("DROP")
                    || upper.contains("CREATE")) {
                directRefreshTree();
            }
        }
    }

    /**
     * Could somebody explain what the purpose of this method is?
     * Contrary to the method name, it looks like it displays
     * results only if gridFormat is off (seems like it  does
     * nothing otherwise, except for clearing help text and moving focus).
     */
    private void updateResult() {

        if (gridFormat) {

            // in case 'help' has removed the grid
            if (bHelp) {
                pResult.removeAll();
                pResult.add(gScrollPane, BorderLayout.CENTER);
                pResult.doLayout();
                gResult.fireTableChanged(null);
                pResult.repaint();

                bHelp = false;
            }
        } else {
            showResultInText();
        }

        txtCommand.selectAll();
        txtCommand.requestFocus();
    }

    /**
     * We let Swing handle displaying nulls (which it generally does by
     * printing nothing for them), except for the case of database
     * VARCHARs, because this is the only class where there is any
     * ambiguity about whether there is a null stored or not.
     */
    private void formatResultSet(ResultSet r) {

        if (r == null) {
            String[] g = new String[1];

            g[0] = "Result";

            gResult.setHead(g);

            g[0] = "(empty)";

            gResult.addRow(g);

            return;
        }

        try {
            ResultSetMetaData m           = r.getMetaData();
            int               col         = m.getColumnCount();
            Object[]          h           = new Object[col];
            boolean[]         nullLiteral = new boolean[col];

            for (int i = 1; i <= col; i++) {
                h[i - 1] = m.getColumnLabel(i);
                switch(m.getColumnType(i)) {
                    case java.sql.Types.CHAR:
                    case java.sql.Types.VARCHAR:
                    case java.sql.Types.VARBINARY:
                        nullLiteral[i - 1] = true;
                }
            }

            gResult.setHead(h);

            while (r.next()) {
                for (int i = 1; i <= col; i++) {
                    try {
                        h[i - 1] = r.getObject(i);

                        if (r.wasNull()) {
                            h[i - 1] = (nullLiteral[i - 1] ? NULL_STR : null);
                        } else if (m.getColumnType(i) == java.sql.Types.VARBINARY ||
                                   m.getColumnType(i) == java.sql.Types.BINARY) {
                            h[i - 1] = r.getString(i);
                        }
                    } catch (SQLException e) {}
                }

                gResult.addRow(h);
            }

            r.close();
        } catch (SQLException e) {

            //  Added: (weconsultants@users)
            CommonSwing.errorMessage(e);
        }
    }

    private void testPerformance() {

        String        all   = txtCommand.getText();
        StringBuilder b     = new StringBuilder();
        long          total = 0;

        lTime = 0;

        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);

            if (c != '\n') {
                b.append(c);
            }
        }

        all = b.toString();

        String[] g = new String[4];

        g[0] = "ms";
        g[1] = "count";
        g[2] = "sql";
        g[3] = "error";

        gResult.setHead(g);

        int max = 1;

        lTime = System.nanoTime() - lTime;

        while (!all.equals("")) {
            int    i = all.indexOf(';');
            String sql;

            if (i != -1) {
                sql = all.substring(0, i);
                all = all.substring(i + 1);
            } else {
                sql = all;
                all = "";
            }

            if (sql.startsWith("--#")) {
                max = Integer.parseInt(sql.substring(3));

                continue;
            } else if (sql.startsWith("--")) {
                continue;
            }

            g[2] = sql;

            long l = 0;

            try {
                l = DatabaseManagerCommon.testStatement(sStatement, sql, max);
                total += l;
                g[0]  = "" + l;
                g[1]  = "" + max;
                g[3]  = "";
            } catch (SQLException e) {
                g[0] = g[1] = "n/a";
                g[3] = e.toString();

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }

            gResult.addRow(g);
            System.out.println(l + " ms : " + sql);
        }

        g[0] = "" + total;
        g[1] = "total";
        g[2] = "";

        gResult.addRow(g);

        lTime = System.nanoTime() - lTime;
    }

    private void showResultInText() {

        Object[] col   = gResult.getHead();
        int      width = col.length;
        int[]    size  = new int[width];
        ArrayList<Object[]> data = gResult.getData();
        Object[] row;
        int      height = data.size();

        for (int i = 0; i < width; i++) {
            size[i] = col[i].toString().length();
        }

        for (int i = 0; i < height; i++) {
            row = data.get(i);

            for (int j = 0; j < width; j++) {
                String item = ((row[j] == null) ? ""
                                                : row[j].toString());
                int    l    = item.length();

                if (l > size[j]) {
                    size[j] = l;
                }
            }
        }

        StringBuilder b = new StringBuilder();

        for (int i = 0; i < width; i++) {
            b.append(col[i]);

            for (int l = col[i].toString().length(); l <= size[i]; l++) {
                b.append(' ');
            }
        }

        b.append(NL);

        for (int i = 0; i < width; i++) {
            for (int l = 0; l < size[i]; l++) {
                b.append('-');
            }

            b.append(' ');
        }

        b.append(NL);

        for (int i = 0; i < height; i++) {
            row = data.get(i);

            for (int j = 0; j < width; j++) {
                String item = ((row[j] == null) ? ""
                                                : row[j].toString());

                b.append(item);

                for (int l = item.length(); l <= size[j]; l++) {
                    b.append(' ');
                }
            }

            b.append(NL);
        }

        // b.append(NL + height + " row(s) in " + lTime + " ms");
        // There is no reason why this report should be text-output-specific.
        // Moving it to bottom of the setWaiting method (where the report
        // gets written to the status line).
        // I'm only doing the rowcount now.  Add the time report there if
        // you are so inclined.
        txtResult.setText(b.toString());
    }

    private void addToRecent(String s) {

        for (int i = 0; i < iMaxRecent; i++) {
            if (s.equals(sRecent[i])) {
                return;
            }
        }

        if (sRecent[iRecent] != null) {
            mRecent.remove(iRecent);
        }

        sRecent[iRecent] = s;

        if (s.length() > 43) {
            s = s.substring(0, 40) + "...";
        }

        JMenuItem item = new JMenuItem(s);

        item.setActionCommand("#" + iRecent);
        item.addActionListener(this);
        mRecent.insert(item, iRecent);

        iRecent = (iRecent + 1) % iMaxRecent;
    }

    // empty implementations for mouse listener.  We're only using
    // mouseReleased
    public final void mouseClicked(final MouseEvent mouseEvent) {}

    public final void mouseEntered(final MouseEvent mouseEvent) {}

    public final void mouseExited(final MouseEvent mouseEvent) {}

    // Check for handlePopup in both mousePressed and mouseReleased.  According to
    // MouseEvent javadocs it's necessary for cross platform compatibility.
    // We keep a record of the last alreadyHandled mouseEvent so we don't do it twice.
    private MouseEvent alreadyHandled = null;

    // mousePressed calls handlePopup, which creates the context-sensitive
    // helper menu.
    public final void mousePressed(final MouseEvent e) {

        if (alreadyHandled == e) {
            return;
        }

        handlePopup(e);

        alreadyHandled = e;
    }

    // mouseReleased calls handlePopup, which creates the context-sensitive
    // helper menu.
    public final void mouseReleased(final MouseEvent e) {

        if (alreadyHandled == e) {
            return;
        }

        handlePopup(e);

        alreadyHandled = e;
    }

    // based on the table or column right-clicked on, create some helper
    // actions for common sql statements
    public final void handlePopup(MouseEvent e) {

        //System.out.println("Handle popup");
        // if this is not a mouse action for popups then do nothing and return
        if (!e.isPopupTrigger()) {
            return;
        }

        // make sure the source of this mouse event was from the tree
        Object source = e.getSource();

        if (!(source instanceof JTree)) {
            return;
        }

        JTree    tree     = (JTree) source;
        TreePath treePath = tree.getPathForLocation(e.getX(), e.getY());

        // if we couldn't find a tree path that corresponds to the
        // right-click, then return
        if (treePath == null) {
            return;
        }

        // create the popup and menus
        JPopupMenu popup = new JPopupMenu();
        JMenuItem  menuItem;
        String[]   menus = new String[] {
            "Select", "Delete", "Update", "Insert"
        };

        // loop throught the menus we want to create, making a PopupListener
        // for each one
        for (int i = 0; i < menus.length; i++) {
            PopupListener popupListener = new PopupListener(menus[i],
                treePath);
            String title = popupListener.toString();

            if (title == null) {
                return;
            }

            // Some of the menu names can be quite long (especially insert).
            // If it's too long, abbreviate it
            if (title.length() > 40) {
                title = title.substring(0, 40) + "...";
            }

            menuItem = new JMenuItem(title);

            menuItem.addActionListener(popupListener);
            popup.add(menuItem);
        }

        popup.show(e.getComponent(), e.getX(), e.getY());
    }

    // handles the creation of the command when a popup is triggered
    private class PopupListener implements ActionListener {

        // used to identify depth while right clicking in tree.
        public static final int DEPTH_URL    = 1;
        public static final int DEPTH_TABLE  = 2;
        public static final int DEPTH_COLUMN = 3;
        String                  command;
        TreePath                treePath;
        TreePath                tablePath;
        TreePath                columnPath;
        String                  table  = null;
        String                  column = null;

        PopupListener(String command, TreePath treePath) {

            super();

            this.command  = command;
            this.treePath = treePath;
        }

        // when the popup is triggered, create a command string and set it in
        // the txtCommand buffer
        public void actionPerformed(ActionEvent ae) {
            txtCommand.setText(getCommandString());
        }

        // text to display when added to a menu
        public String toString() {
            return getCommandString();
        }

        //
        public String getCommandString() {

            int treeDepth = treePath.getPathCount();

            // if we are at TABLE depth, set tablePath and table for use later
            if (treeDepth == DEPTH_URL) {
                return "";
            }

            if (treeDepth == DEPTH_TABLE) {
                tablePath = treePath;
                table = treePath.getPathComponent(DEPTH_TABLE - 1).toString();
            }

            // if we are at TABLE depth, set columnPath, column, tablePath and
            // table for use later
            if (treeDepth == DEPTH_COLUMN) {
                tablePath  = treePath.getParentPath();
                table = treePath.getPathComponent(DEPTH_TABLE - 1).toString();
                columnPath = treePath;
                column = treePath.getPathComponent(DEPTH_COLUMN
                                                   - 1).toString();
            }

            // handle command "SELECT".  Use table and column if set.
            if (command.toUpperCase().equals("SELECT")) {
                String result = "SELECT * FROM " + quoteTableName(table);

                if (column != null) {
                    DefaultMutableTreeNode childNode =
                        (DefaultMutableTreeNode) treePath
                            .getLastPathComponent();
                    String  childName;
                    boolean isChar;

                    if (childNode.getChildCount() > 0) {
                        childName = childNode.getFirstChild().toString();
                        isChar    = childName.contains("CHAR");
                        result    += " WHERE " + quoteObjectName(column);

                        if (isChar) {
                            result += " LIKE '%%'";
                        } else {
                            result += " = ";
                        }
                    }
                }

                return result;
            }

            // handle command "UPDATE".  Use table and column if set.
            else if (command.toUpperCase().equals("UPDATE")) {
                String result = "UPDATE " + quoteTableName(table) + " SET ";

                if (column != null) {
                    result += quoteObjectName(column) + " = ";
                }

                return result;
            }

            // handle command "DELETE".  Use table and column if set.
            else if (command.toUpperCase().equals("DELETE")) {
                String result = "DELETE FROM " + quoteTableName(table);

                if (column != null) {
                    DefaultMutableTreeNode childNode =
                        (DefaultMutableTreeNode) treePath
                            .getLastPathComponent();
                    String  childName;
                    boolean isChar;

                    if (childNode.getChildCount() > 0) {
                        childName = childNode.getFirstChild().toString();
                        isChar    = childName.contains("CHAR");
                        result    += " WHERE " + quoteObjectName(column);

                        if (isChar) {
                            result += " LIKE '%%'";
                        } else {
                            result += " = ";
                        }
                    }
                }

                return result;
            }

            // handle command "INSERT".  Use table and column if set.
            else if (command.toUpperCase().equals("INSERT")) {
                TreeNode    tableNode;
                Enumeration enumer;
                String      columns = "";
                String      values  = " ";
                String      comma   = "";
                String      quote;

                // build a string that includes all the columns that need to
                // be added, with a parenthesied list of commas, suitable for
                // inserting values into.
                if (tablePath == null) {
                    return null;
                }

                tableNode = (TreeNode) tablePath.getLastPathComponent();
                enumer    = tableNode.children();

                while (enumer.hasMoreElements()) {
                    Object o = enumer.nextElement();

                    if (o.toString().equals("Indices")) {
                        continue;
                    }

                    DefaultMutableTreeNode childNode =
                        (DefaultMutableTreeNode) o;
                    String childName;

                    if (childNode.getChildCount() == 0) {
                        continue;
                    } else {
                        childName = childNode.getFirstChild().toString();
                    }

                    // If our first child (type) is some sort of char, use ''
                    // in the string.  Makes is more obvious to the user when
                    // they need to use a string
                    if (childName.contains("CHAR")) {
                        quote = "''";
                    } else {
                        quote = "";
                    }

                    columns += comma + quoteObjectName(o.toString());
                    values  += comma + quote;
                    comma   = ", ";
                }

                return "INSERT INTO " + quoteTableName(table) + "\n( "
                       + columns + " )\nVALUES (" + values + ")";
            } else {
                return "Got here in error " + command
                       + ".  Should never happen";
            }
        }
    }

    /**
     * Perform a limited check (inconclusive) and quote object name if required.
     * Gives wrong result if a quoted name contains a dot.
     */
    private String quoteTableName(String name) {

        int dot = name.indexOf(".");

        if (dot < 0) {
            int bracket = name.indexOf(" (");

            if (bracket >= 0) {
                name = name.substring(0, bracket);
            }

            return quoteObjectName(name);
        }

        String partOne = name.substring(0, dot);
        String partTwo = name.substring(dot + 1);
        int    bracket = partTwo.indexOf(" (");

        if (bracket >= 0) {
            partTwo = partTwo.substring(0, bracket);
        }

        return quoteObjectName(partOne) + '.' + quoteObjectName(partTwo);
    }

    /**
     * perform a limited check (inconclusive) and quote object name if required
     */
    private String quoteObjectName(String name) {

/*
        if (name.toUpperCase().equals(name) && name.indexOf(' ') < 0) {
            return name;
        }
*/
        return "\"" + name + "\"";
    }

    private void initGUI() {

        JPanel pCommand = new JPanel();

        pResult = new JPanel();
        nsSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pCommand,
                                     pResult);

        // Added: (weconsultants@users)
        nsSplitPane.setOneTouchExpandable(true);
        pCommand.setLayout(new BorderLayout());
        pResult.setLayout(new BorderLayout());

        Font fFont = new Font("Dialog", Font.PLAIN, 12);

        txtCommand = new JTextArea(7, 40);

        txtCommand.setMargin(new Insets(5, 5, 5, 5));
        txtCommand.addKeyListener(this);

        txtCommandScroll = new JScrollPane(txtCommand);
        txtResult        = new JTextArea(25, 40);

        txtResult.setMargin(new Insets(5, 5, 5, 5));

        txtResultScroll = new JScrollPane(txtResult);

        txtCommand.setFont(fFont);
        txtResult.setFont(new Font("Courier", Font.PLAIN, 12));
        pCommand.add(txtCommandScroll, BorderLayout.CENTER);

        gResult = new GridSwing();

        TableSorter sorter = new TableSorter(gResult);

        tableModel   = sorter;
        gResultTable = new JTable(sorter);

        sorter.setTableHeader(gResultTable.getTableHeader());

        gScrollPane = new JScrollPane(gResultTable);

        gResultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        gResult.setJTable(gResultTable);

        //getContentPane().setLayout(new BorderLayout());
        pResult.add(gScrollPane, BorderLayout.CENTER);

        // Set up the tree
        rootNode    = new DefaultMutableTreeNode("Connection");
        treeModel   = new DefaultTreeModel(rootNode);
        tTree       = new JTree(treeModel);
        tScrollPane = new JScrollPane(tTree);

        // System.out.println("Adding mouse listener");
        tTree.addMouseListener(this);
        tScrollPane.setPreferredSize(new Dimension(200, 400));
        tScrollPane.setMinimumSize(new Dimension(70, 100));
        txtCommandScroll.setPreferredSize(new Dimension(560, 100));
        txtCommandScroll.setMinimumSize(new Dimension(180, 100));
        gScrollPane.setPreferredSize(new Dimension(460, 300));

        ewSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tScrollPane,
                                     nsSplitPane);

        // Added: (weconsultants@users)
        ewSplitPane.setOneTouchExpandable(true);
        fMain.getContentPane().add(ewSplitPane, BorderLayout.CENTER);

        // Added: (weconsultants@users)
        jStatusLine = new JLabel();
        iReadyStatus =
            new JButton(new ImageIcon(CommonSwing.getIcon("StatusReady")));

        iReadyStatus.setSelectedIcon(
            new ImageIcon(CommonSwing.getIcon("StatusRunning")));

        pStatus = new JPanel();

        pStatus.setLayout(new BorderLayout());
        pStatus.add(iReadyStatus, BorderLayout.WEST);
        pStatus.add(jStatusLine, BorderLayout.CENTER);
        fMain.getContentPane().add(pStatus, "South");
        doLayout();

        if (fMain instanceof java.awt.Window) {
            ((java.awt.Window) fMain).pack();
        } else {
            ((Container) fMain).validate();
        }
    }

    /* Simple tree node factory method - set's parent and user object.
     */
    private DefaultMutableTreeNode makeNode(Object userObject,
            MutableTreeNode parent) {

        DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);

        if (parent != null) {
            treeModel.insertNodeInto(node, parent, parent.getChildCount());
        }

        return node;
    }

    private static final String[] usertables   = {
        "TABLE", "GLOBAL TEMPORARY", "VIEW", "SYSTEM TABLE"
    };
    private static final String[] nonSystables = {
        "TABLE", "GLOBAL TEMPORARY", "VIEW"
    };
    private static final HashSet<String> oracleSysUsers = new HashSet<String>();
    private static final String[] oracleSysSchemas = {
        "SYS", "SYSTEM", "OUTLN", "DBSNMP", "OUTLN", "MDSYS", "ORDSYS",
        "ORDPLUGINS", "CTXSYS", "DSSYS", "PERFSTAT", "WKPROXY", "WKSYS",
        "WMSYS", "XDB", "ANONYMOUS", "ODM", "ODM_MTR", "OLAPSYS", "TRACESVR",
        "REPADMIN"
    };

    static {
        Collections.addAll(oracleSysUsers, oracleSysSchemas);
    }

    /**
     * Schedules to run in a Gui-safe thread
     */
    protected void refreshTree() {
        backgroundIt(treeRefreshRunnable, "Refreshing object tree");
    }

    /**
     * Clear all existing nodes from the tree model and rebuild from scratch.
     *
     * This method executes in current thread
     */
    protected void directRefreshTree() {

        int[]                  rowCounts;
        DefaultMutableTreeNode propertiesNode;

        // Added: (weconsultants@users) Moved tableNode here for visibiity nd new DECFM
        DefaultMutableTreeNode tableNode;
        DecimalFormat DECFMT = new DecimalFormat(" ( ####,###,####,##0 )");

        // First clear the existing tree by simply enumerating
        // over the root node's children and removing them one by one.
        while (treeModel.getChildCount(rootNode) > 0) {
            DefaultMutableTreeNode child =
                (DefaultMutableTreeNode) treeModel.getChild(rootNode, 0);

            treeModel.removeNodeFromParent(child);
            child.removeAllChildren();
            child.removeFromParent();
        }

        treeModel.nodeStructureChanged(rootNode);
        treeModel.reload();
        tScrollPane.repaint();

        if (dMeta == null) {
            return;
        }

        ResultSet result = null;

        // Now rebuild the tree below its root
        try {

            // Start by naming the root node from its URL:
            rootNode.setUserObject(dMeta.getURL());

            // get metadata about user tables by building a vector of table names
            result = dMeta.getTables(null, null, null, (showSys ? usertables
                                                                : nonSystables));

            ArrayList<String> tables  = new ArrayList<String>();
            ArrayList<String> schemas = new ArrayList<String>();

            // sqlbob@users Added remarks.
            ArrayList<String> remarks = new ArrayList<String>();
            String schema;

            while (result.next()) {
                schema = result.getString(2);

                if ((!showSys) && isOracle
                        && oracleSysUsers.contains(schema)) {
                    continue;
                }

                if (schemaFilter == null || schema.equals(schemaFilter)) {
                    schemas.add(schema);
                    tables.add(result.getString(3));
                    remarks.add(result.getString(5));

                    continue;
                }
            }

            result.close();

            result = null;

            // Added: (weconsultants@users)
            // Sort not to go into production. Have to sync with 'remarks Vector' for DBMS that has it
            //   Collections.sort(tables);
            // Added: (weconsultants@users) - Add rowCounts if needed.
            rowCounts = new int[tables.size()];

            try {
                rowCounts = getRowCounts(tables, schemas);
            } catch (Exception e) {

                //  Added: (weconsultants@users)
                CommonSwing.errorMessage(e);
            }

            ResultSet col;

            // For each table, build a tree node with interesting info
            for (int i = 0; i < tables.size(); i++) {
                col = null;

                String name;

                try {
                    name = tables.get(i);

                    if (isOracle && name.startsWith("BIN$")) {
                        continue;

                        // Oracle Recyle Bin tables.
                        // Contains metacharacters which screw up metadata
                        // queries below.
                    }

                    schema = schemas.get(i);

                    String schemaname = "";

                    if (schema != null && showSchemas) {
                        schemaname = schema + '.';
                    }

                    String rowcount = displayRowCounts
                                      ? (DECFMT.format(rowCounts[i]))
                                      : "";
                    String displayedName = schemaname + name + rowcount;

                    // weconsul@ptd.net Add rowCounts if needed.
                    tableNode = makeNode(displayedName, rootNode);
                    col       = dMeta.getColumns(null, schema, name, null);

                    if ((schema != null) && !schema.trim().equals("")) {
                        makeNode(schema, tableNode);
                    }

                    // sqlbob@users Added remarks.
                    String remark = remarks.get(i);

                    if ((remark != null) && !remark.trim().equals("")) {
                        makeNode(remark, tableNode);
                    }

                    // This block is very slow for some Oracle tables.
                    // With a child for each column containing pertinent attributes
                    while (col.next()) {
                        String c = col.getString(4);
                        DefaultMutableTreeNode columnNode = makeNode(c,
                            tableNode);
                        String type = col.getString(6);

                        makeNode("Type: " + type, columnNode);

                        boolean nullable = col.getInt(11)
                                           != DatabaseMetaData.columnNoNulls;

                        makeNode("Nullable: " + nullable, columnNode);
                    }
                } finally {
                    if (col != null) {
                        try {
                            col.close();
                        } catch (SQLException se) {}
                    }
                }

                DefaultMutableTreeNode indexesNode = makeNode("Indices",
                    tableNode);

                if (showIndexDetails) {
                    ResultSet ind = null;

                    try {
                        ind = dMeta.getIndexInfo(null, schema, name, false,
                                                 false);

                        String                 oldiname  = null;
                        DefaultMutableTreeNode indexNode = null;

                        // A child node to contain each index - and its attributes
                        while (ind.next()) {
                            boolean nonunique = ind.getBoolean(4);
                            String  iname     = ind.getString(6);

                            if ((oldiname == null
                                    || !oldiname.equals(iname))) {
                                indexNode = makeNode(iname, indexesNode);

                                makeNode("Unique: " + !nonunique, indexNode);

                                oldiname = iname;
                            }

                            // And the ordered column list for index components
                            makeNode(ind.getString(9), indexNode);
                        }
                    } catch (SQLException se) {

                        // Workaround for Oracle
                        if (se.getMessage() == null || ((!se.getMessage()
                                .startsWith("ORA-25191:")) && (!se.getMessage()
                                .startsWith("ORA-01702:")) && !se.getMessage()
                                    .startsWith("ORA-01031:"))) {
                            throw se;
                        }
                    } finally {
                        if (ind != null) {
                            ind.close();
                        }
                    }
                }
            }

            // Finally - a little additional metadata on this connection
            propertiesNode = makeNode("Properties", rootNode);

            makeNode("User: " + dMeta.getUserName(), propertiesNode);
            makeNode("ReadOnly: " + cConn.isReadOnly(), propertiesNode);
            makeNode("AutoCommit: " + cConn.getAutoCommit(), propertiesNode);
            makeNode("Driver: " + dMeta.getDriverName(), propertiesNode);
            makeNode("Product: " + dMeta.getDatabaseProductName(),
                     propertiesNode);
            makeNode("Version: " + dMeta.getDatabaseProductVersion(),
                     propertiesNode);
        } catch (SQLException se) {
            propertiesNode = makeNode("Error getting metadata:", rootNode);

            makeNode(se.getMessage(), propertiesNode);
            makeNode(se.getSQLState(), propertiesNode);
            CommonSwing.errorMessage(se);
        } finally {
            if (result != null) {
                try {
                    result.close();
                } catch (SQLException se) {}
            }
        }

        treeModel.nodeStructureChanged(rootNode);
        treeModel.reload();
        tScrollPane.repaint();

        // We want the Schema List to always be in sync with the displayed tree
        updateSchemaList();
    }

    // Added: (weconsultants@users) Sets up\changes the running status icon
    void setStatusLine(String busyBaseString, int rowCount) {

        iReadyStatus.setSelected(busyBaseString != null);

        if (busyBaseString == null) {
            String additionalMsg = "";

            if (schemaFilter != null) {
                additionalMsg = " /  Tree showing objects in schema '"
                                + schemaFilter + "'";
            }

            long millis   = lTime / 1000000;
            long fraction = (lTime % 1000000) / 100000;

            additionalMsg += " / " + rowCount + " rows retrieved in "
                                 + millis + '.' + fraction + " ms";

            jStatusLine.setText("  " + READY_STATUS + additionalMsg);
        } else {
            jStatusLine.setText("  " + busyBaseString + "...");
        }
    }

    // Added: (weconsultants@users) Needed to aggregate counts per table in jTree
    protected int[] getRowCounts(ArrayList inTable,
                                 ArrayList inSchema) {

        if (!displayRowCounts) {
            return (null);
        }

        String rowCountSelect = "SELECT COUNT(*) FROM ";
        int[]  counts;
        String name;

        counts = new int[inTable.size()];

        try {
            Statement select = rowConn.createStatement();

            for (int i = 0; i < inTable.size(); i++) {
                try {
                    String schemaPart = (String) inSchema.get(i);

                    schemaPart = schemaPart == null ? ""
                                                    : ("\"" + schemaPart
                                                       + "\".\"");
                    name = schemaPart + inTable.get(i) + "\"";

                    ResultSet resultSet = select.executeQuery(rowCountSelect
                        + name);

                    while (resultSet.next()) {
                        counts[i] = resultSet.getInt(1);
                    }
                } catch (Exception e) {
                    System.err.println("Unable to get row count for table "
                                       + inSchema.get(i) + '.'
                                       + inTable.get(i)
                                       + ".  Using value '0': " + e);
                }
            }
        } catch (Exception e) {
            CommonSwing.errorMessage(e);
        }

        return (counts);
    }

    protected JToolBar createToolBar() {

        // Build jtoolbar and jtoolbar Buttons
        JToolBar jtoolbar = new JToolBar();

        jtoolbar.putClientProperty("JToolBar.isRollover", Boolean.TRUE);

        // I'm dropping "Statement" from  "Execute SQL Statement", etc.,
        // because it may or may not be "one statement", but it is SQL.
        // Build jbuttonClear Buttons - blaine
        jbuttonClear =
            new JButton("Clear SQL",
                        new ImageIcon(CommonSwing.getIcon("Clear")));

        jbuttonClear.putClientProperty("is3DEnabled", Boolean.TRUE);
        tipMap.put(jbuttonClear, "Clear SQL");
        jbuttonClear.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {

                if (sqlScriptBuffer == null
                        && txtCommand.getText().length() < 1) {
                    CommonSwing.errorMessage("No SQL to clear");

                    return;
                }

                clear();
            }
        });

        jbuttonExecute =
            new JButton("Execute SQL",
                        new ImageIcon(CommonSwing.getIcon("Execute")));

        tipMap.put(jbuttonExecute, "Execute SQL");
        jbuttonExecute.putClientProperty("is3DEnabled", Boolean.TRUE);
        jbuttonExecute.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent actionevent) {
                executeCurrentSQL();
            }
        });
        jtoolbar.addSeparator();
        jtoolbar.add(jbuttonClear);
        jtoolbar.addSeparator();
        jtoolbar.add(jbuttonExecute);
        jtoolbar.addSeparator();
        jbuttonClear.setAlignmentY(0.5F);
        jbuttonClear.setAlignmentX(0.5F);
        jbuttonExecute.setAlignmentY(0.5F);
        jbuttonExecute.setAlignmentX(0.5F);

        return jtoolbar;
    }

    void updateAutoCommitBox() {

        try {
            if (cConn != null) {
                boxAutoCommit.setSelected(cConn.getAutoCommit());
            }
        } catch (SQLException se) {
            CommonSwing.errorMessage(se);
        }
    }

    private void setLF(String newLAF) {

        if (currentLAF != null && currentLAF == newLAF) {    // No change
            return;
        }

        if (pResult != null && gridFormat) {
            pResult.removeAll();
        }

        CommonSwing.setSwingLAF((Component) fMain, newLAF);

        if (pResult != null && gridFormat) {
            setResultsInGrid();
        }

        currentLAF = newLAF;

        if (currentLAF.equals(CommonSwing.Native)) {
            rbNativeLF.setSelected(true);
        } else if (currentLAF.equals(CommonSwing.Java)) {
            rbJavaLF.setSelected(true);
        } else if (currentLAF.equals(CommonSwing.Motif)) {
            rbMotifLF.setSelected(true);
        }
    }

    void resetTooltips() {

        Iterator<AbstractButton>   it = tipMap.keySet().iterator();
        JComponent component;

        while (it.hasNext()) {
            component = it.next();

            component.setToolTipText(showTooltips
                                     ? tipMap.get(component)
                                     : null);
        }
    }

    private void updateSchemaList() {

        ButtonGroup       group  = new ButtonGroup();
        ArrayList<String> list   = new ArrayList<String>();
        ResultSet         result = null;

        try {
            result = dMeta.getSchemas();

            if (result == null) {
                throw new SQLException("Failed to get metadata from database");
            }

            while (result.next()) {
                list.add(result.getString(1));
            }
        } catch (SQLException se) {
            CommonSwing.errorMessage(se);
        } finally {
            if (result != null) {
                try {
                    result.close();
                } catch (SQLException se) {}
            }
        }

        mnuSchemas.removeAll();
        rbAllSchemas.setSelected(schemaFilter == null);
        group.add(rbAllSchemas);
        mnuSchemas.add(rbAllSchemas);

        String               s;
        JRadioButtonMenuItem radioButton;

        for (int i = 0; i < list.size(); i++) {
            s           = list.get(i);
            radioButton = new JRadioButtonMenuItem(s);

            group.add(radioButton);
            mnuSchemas.add(radioButton);
            radioButton.setSelected(schemaFilter != null
                                    && schemaFilter.equals(s));
            radioButton.addActionListener(schemaListListener);
            radioButton.setEnabled(list.size() > 1);
        }

        mnuSchemas.addSeparator();
        mnuSchemas.add(mitemUpdateSchemas);
    }

    ActionListener schemaListListener = (new ActionListener() {

        public void actionPerformed(ActionEvent actionevent) {

            schemaFilter = actionevent.getActionCommand();

            if (schemaFilter.equals("*")) {
                schemaFilter = null;
            }

            refreshTree();
        }
    });

    /**
     * Persisted User Preferences for DatabaseManagerSwing.
     *
     * These are settings for items in the View and Options pulldown menus,
     * plus Help/Show Tooltips.
     */
    public static class DBMPrefs {

        public File prefsFile = null;

        /**
         * The constructor guarantees that this will be null for Applet,
         *  non-null if using a local preferences file
         */

        // Set defaults from Data
        boolean autoRefresh   = true;
        boolean showRowCounts = false;
        boolean showSysTables = false;
        boolean showSchemas   = true;
        boolean resultGrid    = true;
        String  laf           = CommonSwing.Native;

        // Somebody with more time can store the font settings.  IMO, that
        // menu item shouldn't even be there if the settings aren't persisted.
        boolean showTooltips = true;

        public DBMPrefs(boolean isApplet) throws IOException {

            if (!isApplet) {
                if (homedir == null) {
                    throw new IOException(
                        "Skipping preferences since do not know home dir");
                }

                prefsFile = new File(homedir, "dbmprefs.properties");
            }

            load();
        }

        public void load() throws IOException {

            String tmpString;

            if (prefsFile != null) {

                // LOAD PREFERENCES FROM LOCAL PREFERENCES FILE
                if (!prefsFile.exists()) {
                    throw new IOException("No such file: " + prefsFile);
                }

                Properties props = new Properties();

                try {
                    FileInputStream fis = new FileInputStream(prefsFile);

                    props.load(fis);
                    fis.close();
                } catch (IOException ioe) {
                    throw new IOException("Failed to read preferences file '"
                                          + prefsFile + "':  "
                                          + ioe.getMessage());
                }

                tmpString = props.getProperty("autoRefresh");

                if (tmpString != null) {
                    autoRefresh = Boolean.valueOf(tmpString).booleanValue();
                }

                tmpString = props.getProperty("showRowCounts");

                if (tmpString != null) {
                    showRowCounts = Boolean.valueOf(tmpString).booleanValue();
                }

                tmpString = props.getProperty("showSysTables");

                if (tmpString != null) {
                    showSysTables = Boolean.valueOf(tmpString).booleanValue();
                }

                tmpString = props.getProperty("showSchemas");

                if (tmpString != null) {
                    showSchemas = Boolean.valueOf(tmpString).booleanValue();
                }

                tmpString = props.getProperty("resultGrid");

                if (tmpString != null) {
                    resultGrid = Boolean.valueOf(tmpString).booleanValue();
                }

                tmpString = props.getProperty("laf");
                laf       = ((tmpString == null) ? CommonSwing.Native
                                                 : tmpString);
                tmpString = props.getProperty("showTooltips");

                if (tmpString != null) {
                    showTooltips = Boolean.valueOf(tmpString).booleanValue();
                }
            }
        }

        public void store() {

            if (prefsFile == null) {

                // Can't persist Applet settings.
                return;
            }

            Properties props = new Properties();

            // Boolean.toString(boolean) was new with Java 1.4, so don't use that.
            props.setProperty("autoRefresh", (autoRefresh ? tString
                                                          : fString));
            props.setProperty("showRowCounts", (showRowCounts ? tString
                                                              : fString));
            props.setProperty("showSysTables", (showSysTables ? tString
                                                              : fString));
            props.setProperty("showSchemas", (showSchemas ? tString
                                                          : fString));
            props.setProperty("resultGrid", (resultGrid ? tString
                                                        : fString));
            props.setProperty("laf", laf);
            props.setProperty("showTooltips", (showTooltips ? tString
                                                            : fString));

            try {
                FileOutputStream fos = new FileOutputStream(prefsFile);

                props.store(fos, "DatabaseManagerSwing user preferences");
                fos.flush();
                fos.close();
            } catch (IOException ioe) {
                throw new RuntimeException(
                    "Failed to prepare preferences file '" + prefsFile
                    + "':  " + ioe.getMessage());
            }
        }
    }

    private static void setLogToSystem(boolean value) {

        try {
            PrintWriter newPrintWriter = (value) ? new PrintWriter(System.out)
                                                 : null;

            DriverManager.setLogWriter(newPrintWriter);
        } catch (Exception e) {}
    }

    private static final String tString = Boolean.TRUE.toString();
    private static final String fString = Boolean.FALSE.toString();
}
