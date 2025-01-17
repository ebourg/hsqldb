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
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.lib.ArrayUtil;
import org.hsqldb.lib.StringUtil;
import org.hsqldb.types.TimestampData;
import org.hsqldb.types.Types;

/**
 * collection of static methods to convert Date and Timestamp strings
 * into corresponding Java objects and perform other Calendar related
 * operation.<p>
 *
 * Was reviewed for 1.7.2 resulting in centralising all DATETIME related
 * operations.<p>
 *
 * From version 2.0.0, HSQLDB supports TIME ZONE with datetime types. The
 * values are stored internally as UTC seconds from 1970, regardless of the
 * time zone of the JVM, and converted as and when required, to the local
 * timezone.
 *
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.7.0
 * @since 1.7.0
 */
public class HsqlDateTime {

    public static final Locale    defaultLocale  = Locale.UK;
    private static final Calendar tempCalDefault = new GregorianCalendar();
    private static final Calendar tempCalGMT =
        new GregorianCalendar(TimeZone.getTimeZone("GMT"), defaultLocale);
    private static final String sdfdPattern = "yyyy-MM-dd";
    private static final SimpleDateFormat sdfd =
        new SimpleDateFormat(sdfdPattern);
    private static final String sdftPattern = "HH:mm:ss";
    private static final SimpleDateFormat sdft =
        new SimpleDateFormat(sdftPattern);
    private static final String sdftsPattern = "yyyy-MM-dd HH:mm:ss";
    private static final SimpleDateFormat sdfts =
        new SimpleDateFormat(sdftsPattern);
    private static final String sdftsSysPattern = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final SimpleDateFormat sdftsSys =
        new SimpleDateFormat(sdftsSysPattern);
    private static final Date sysDate = new java.util.Date();

    static {
        TimeZone.getDefault();
        tempCalGMT.setLenient(false);
        sdfd.setCalendar(new GregorianCalendar(TimeZone.getTimeZone("GMT"),
                                               defaultLocale));
        sdfd.setLenient(false);
        sdft.setCalendar(new GregorianCalendar(TimeZone.getTimeZone("GMT"),
                                               defaultLocale));
        sdft.setLenient(false);
        sdfts.setCalendar(new GregorianCalendar(TimeZone.getTimeZone("GMT"),
                defaultLocale));
        sdfts.setLenient(false);
    }

    public static long getDateSeconds(String s) {

        try {
            synchronized (sdfd) {
                java.util.Date d = sdfd.parse(s);

                return d.getTime() / 1000;
            }
        } catch (Exception e) {
            throw Error.error(ErrorCode.X_22007);
        }
    }

    public static String getDateString(long seconds) {

        synchronized (sdfd) {
            sysDate.setTime(seconds * 1000);

            return sdfd.format(sysDate);
        }
    }

    public static long getTimestampSeconds(String s) {

        try {
            synchronized (sdfts) {
                java.util.Date d = sdfts.parse(s);

                return d.getTime() / 1000;
            }
        } catch (Exception e) {
            throw Error.error(ErrorCode.X_22007);
        }
    }

    public static String getTimestampString(long seconds, int nanos,
            int scale) {

        synchronized (sdfts) {
            sysDate.setTime(seconds * 1000);

            String ts = sdfts.format(sysDate);

            if (scale > 0) {
                ts += '.' + StringUtil.toZeroPaddedString(nanos, 9, scale);
            }

            return ts;
        }
    }

    public static String getTimestampString(long millis) {

        synchronized (sdfts) {
            sysDate.setTime(millis);

            return sdfts.format(sysDate);
        }
    }

    private static void resetToDate(Calendar cal) {

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private static void resetToTime(Calendar cal) {

        cal.set(Calendar.YEAR, 1970);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DATE, 1);
        cal.set(Calendar.MILLISECOND, 0);
    }

    public static long convertMillisToCalendar(Calendar calendar,
            long millis) {

        synchronized (tempCalGMT) {
            synchronized (calendar) {
                calendar.clear();
                tempCalGMT.setTimeInMillis(millis);
                calendar.set(tempCalGMT.get(Calendar.YEAR),
                             tempCalGMT.get(Calendar.MONTH),
                             tempCalGMT.get(Calendar.DAY_OF_MONTH),
                             tempCalGMT.get(Calendar.HOUR_OF_DAY),
                             tempCalGMT.get(Calendar.MINUTE),
                             tempCalGMT.get(Calendar.SECOND));

                return calendar.getTimeInMillis();
            }
        }
    }

    public static long convertMillisFromCalendar(Calendar sourceCalendar,
            Calendar targetClendar, long millis) {

        synchronized (targetClendar) {
            synchronized (sourceCalendar) {
                targetClendar.clear();
                sourceCalendar.setTimeInMillis(millis);
                targetClendar.set(sourceCalendar.get(Calendar.YEAR),
                                  sourceCalendar.get(Calendar.MONTH),
                                  sourceCalendar.get(Calendar.DAY_OF_MONTH),
                                  sourceCalendar.get(Calendar.HOUR_OF_DAY),
                                  sourceCalendar.get(Calendar.MINUTE),
                                  sourceCalendar.get(Calendar.SECOND));

                return targetClendar.getTimeInMillis();
            }
        }
    }

    public static long convertSecondsFromCalendar(Calendar sourceCalendar,
            Calendar targetClendar, long seconds) {

        synchronized (targetClendar) {
            synchronized (sourceCalendar) {
                targetClendar.clear();
                sourceCalendar.setTimeInMillis(seconds * 1000);
                targetClendar.set(sourceCalendar.get(Calendar.YEAR),
                                  sourceCalendar.get(Calendar.MONTH),
                                  sourceCalendar.get(Calendar.DAY_OF_MONTH),
                                  sourceCalendar.get(Calendar.HOUR_OF_DAY),
                                  sourceCalendar.get(Calendar.MINUTE),
                                  sourceCalendar.get(Calendar.SECOND));

                return targetClendar.getTimeInMillis() / 1000;
            }
        }
    }

    /**
     * Sets the time in the given Calendar using the given milliseconds value; wrapper method to
     * allow use of more efficient JDK1.4 method on JDK1.4 (was protected in earlier versions).
     *
     * @param       cal                             the Calendar
     * @param       millis                  the time value in milliseconds
     */
    public static void setTimeInMillis(Calendar cal, long millis) {
        cal.setTimeInMillis(millis);
    }

    public static long convertToNormalisedTime(long t) {
        return convertToNormalisedTime(tempCalGMT, t);
    }

    public static long convertToNormalisedTime(Calendar cal, long t) {

        synchronized (cal) {
            setTimeInMillis(cal, t);
            resetToDate(cal);

            long t1 = cal.getTimeInMillis();

            return t - t1;
        }
    }

    public static long getNormalisedTime(long t) {
        return getNormalisedTime(tempCalGMT, t);
    }

    public static long getNormalisedTime(Calendar calendar, long t) {

        synchronized (calendar) {
            setTimeInMillis(calendar, t);
            resetToTime(calendar);

            return calendar.getTimeInMillis();
        }
    }

    public static long getNormalisedDate(long d) {
        return getNormalisedDate(tempCalGMT, d);
    }

    public static long getNormalisedDate(Calendar calendar, long t) {

        synchronized (calendar) {
            setTimeInMillis(calendar, t);
            resetToDate(calendar);

            return calendar.getTimeInMillis();
        }
    }

    public static int getZoneSeconds() {
        return getZoneSeconds(tempCalDefault);
    }

    public static int getZoneSeconds(Calendar calendar) {
        return (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET))
               / 1000;
    }

    public static int getZoneMillis(Calendar calendar, long millis) {
        return calendar.getTimeZone().getOffset(millis);
    }

    /**
     * Returns the indicated part of the given millisecond date object.
     * @param m the millisecond time value from which to extract the indicated part
     * @param part an integer code corresponding to the desired date part
     * @return the indicated part of the given <code>java.util.Date</code> object
     */
    public static int getDateTimePart(Calendar calendar, long m, int part) {

        synchronized (calendar) {
            calendar.setTimeInMillis(m);

            return calendar.get(part);
        }
    }

    /**
     * truncates millisecond date object
     */
    public static long getTruncatedPart(Calendar calendar, long m, int part) {

        synchronized (calendar) {
            calendar.setTimeInMillis(m);

            switch (part) {

                case Types.DTI_ISO_WEEK_OF_YEAR : {
                    int dayWeek = calendar.get(Calendar.DAY_OF_WEEK);

                    if (dayWeek == 1) {
                        dayWeek = 8;
                    }

                    calendar.add(Calendar.DAY_OF_YEAR, 2 - dayWeek);
                    resetToDate(calendar);

                    break;
                }
                case Types.DTI_WEEK_OF_YEAR : {
                    int dayWeek = calendar.get(Calendar.DAY_OF_WEEK);

                    calendar.add(Calendar.DAY_OF_YEAR, 1 - dayWeek);
                    resetToDate(calendar);

                    break;
                }
                default : {
                    zeroFromPart(calendar, part);

                    break;
                }
            }

            return calendar.getTimeInMillis();
        }
    }

    /**
     * rounded millisecond date object
     */
    public static long getRoundedPart(Calendar calendar, long m, int part) {

        synchronized (calendar) {
            calendar.setTimeInMillis(m);

            switch (part) {

                case Types.SQL_INTERVAL_YEAR :
                    if (calendar.get(Calendar.MONTH) > 6) {
                        calendar.add(Calendar.YEAR, 1);
                    }
                    break;

                case Types.SQL_INTERVAL_MONTH :
                    if (calendar.get(Calendar.DAY_OF_MONTH) > 15) {
                        calendar.add(Calendar.MONTH, 1);
                    }
                    break;

                case Types.SQL_INTERVAL_DAY :
                    if (calendar.get(Calendar.HOUR_OF_DAY) > 11) {
                        calendar.add(Calendar.DAY_OF_MONTH, 1);
                    }
                    break;

                case Types.SQL_INTERVAL_HOUR :
                    if (calendar.get(Calendar.MINUTE) > 29) {
                        calendar.add(Calendar.HOUR_OF_DAY, 1);
                    }
                    break;

                case Types.SQL_INTERVAL_MINUTE :
                    if (calendar.get(Calendar.SECOND) > 29) {
                        calendar.add(Calendar.MINUTE, 1);
                    }
                    break;

                case Types.SQL_INTERVAL_SECOND :
                    if (calendar.get(Calendar.MILLISECOND) > 499) {
                        calendar.add(Calendar.SECOND, 1);
                    }
                    break;

                case Types.DTI_WEEK_OF_YEAR : {
                    int dayYear = calendar.get(Calendar.DAY_OF_YEAR);
                    int year    = calendar.get(Calendar.YEAR);
                    int week    = calendar.get(Calendar.WEEK_OF_YEAR);
                    int day     = calendar.get(Calendar.DAY_OF_WEEK);

                    calendar.clear();
                    calendar.set(Calendar.YEAR, year);

                    if (day > 3) {
                        week++;
                    }

                    if (week == 1 && (dayYear > 356 || dayYear < 7)) {
                        calendar.set(Calendar.DAY_OF_YEAR, dayYear);

                        while (true) {
                            if (calendar.get(Calendar.DAY_OF_WEEK) == 1) {
                                return calendar.getTimeInMillis();
                            }

                            calendar.add(Calendar.DAY_OF_YEAR, -1);
                        }
                    }

                    calendar.set(Calendar.WEEK_OF_YEAR, week);

                    return calendar.getTimeInMillis();
                }
            }

            zeroFromPart(calendar, part);

            return calendar.getTimeInMillis();
        }
    }

    static void zeroFromPart(Calendar cal, int part) {

        switch (part) {

            case Types.SQL_INTERVAL_YEAR :
                cal.set(Calendar.MONTH, 0);
            case Types.SQL_INTERVAL_MONTH :
                cal.set(Calendar.DAY_OF_MONTH, 1);
            case Types.SQL_INTERVAL_DAY :
                cal.set(Calendar.HOUR_OF_DAY, 0);
            case Types.SQL_INTERVAL_HOUR :
                cal.set(Calendar.MINUTE, 0);
            case Types.SQL_INTERVAL_MINUTE :
                cal.set(Calendar.SECOND, 0);
            case Types.SQL_INTERVAL_SECOND :
                cal.set(Calendar.MILLISECOND, 0);
            default :
        }
    }

    //J-

    private static final char[][] dateTokens     = {
        { 'R', 'R', 'R', 'R' }, { 'I', 'Y', 'Y', 'Y' }, { 'Y', 'Y', 'Y', 'Y' },
        { 'I', 'Y' }, { 'Y', 'Y' },
        { 'B', 'C' }, { 'B', '.', 'C', '.' }, { 'A', 'D' }, { 'A', '.', 'D', '.' },
        { 'M', 'O', 'N' }, { 'M', 'O', 'N', 'T', 'H' },
        { 'M', 'M' },
        { 'D', 'A', 'Y' }, { 'D', 'Y' },
        { 'W', 'W' }, { 'I', 'W' }, { 'D', 'D' }, { 'D', 'D', 'D' },
        { 'W' },
        { 'H', 'H', '2', '4' }, { 'H', 'H', '1', '2' }, { 'H', 'H' },
        { 'M', 'I' },
        { 'S', 'S' },
        { 'A', 'M' }, { 'P', 'M' }, { 'A', '.', 'M', '.' }, { 'P', '.', 'M', '.' },
        { 'F', 'F' }
    };

    private static final String[] javaDateTokens = {
        "yyyy", "'*IYYY'", "yyyy",
        "'*IY'", "yy",
        "G", "G", "G", "G",
        "MMM", "MMMMM",
        "MM",
        "EEEE", "EE",
        "'*WW'", "'*IW'", "dd", "D",
        "'*W'",
        "HH", "KK", "KK",
        "mm", "ss",
        "aaa", "aaa", "aaa", "aaa",
        "SSS"
    };

    private static final int[] sqlIntervalCodes = {
        -1, -1, Types.SQL_INTERVAL_YEAR,
        -1, Types.SQL_INTERVAL_YEAR,
        -1, -1, -1, -1,
        Types.SQL_INTERVAL_MONTH, Types.SQL_INTERVAL_MONTH,
        Types.SQL_INTERVAL_MONTH,
        -1, -1,
        Types.DTI_WEEK_OF_YEAR, Types.DTI_ISO_WEEK_OF_YEAR, Types.SQL_INTERVAL_DAY, Types.SQL_INTERVAL_DAY,
        -1,
        Types.SQL_INTERVAL_HOUR, -1, Types.SQL_INTERVAL_HOUR,
        Types.SQL_INTERVAL_MINUTE,
        Types.SQL_INTERVAL_SECOND,
        -1,-1,-1,-1,
        -1
    };

    //J+

    /** Indicates end-of-input */
    private static final char e = 0xffff;

    public static TimestampData toDate(String string, String pattern,
                                       SimpleDateFormat format,
                                       boolean fraction) {

        long   millis;
        int    nanos       = 0;
        String javaPattern = HsqlDateTime.toJavaDatePattern(pattern);
        String tempPattern = null;
        int    matchIndex  = javaPattern.indexOf("*IY");

        if (matchIndex >= 0) {
            throw Error.error(ErrorCode.X_22511);
        }

        matchIndex = javaPattern.indexOf("*WW");

        if (matchIndex >= 0) {
            throw Error.error(ErrorCode.X_22511);
        }

        matchIndex = javaPattern.indexOf("*W");

        if (matchIndex >= 0) {
            throw Error.error(ErrorCode.X_22511);
        }

        matchIndex = javaPattern.indexOf("SSS");

        if (matchIndex >= 0) {
            tempPattern = javaPattern;
            javaPattern = javaPattern.substring(0, matchIndex)
                          + javaPattern.substring(matchIndex + 3);
        }

        try {
            format.applyPattern(javaPattern);

            millis = format.parse(string).getTime();
        } catch (Exception e) {
            throw Error.error(ErrorCode.X_22007, e.toString());
        }

        if (matchIndex >= 0 && fraction) {
            javaPattern = tempPattern;

            try {
                format.applyPattern(javaPattern);

                long tempMillis = format.parse(string).getTime();
                int  factor     = 1;

                tempMillis -= millis;
                nanos      = (int) tempMillis;

                while (tempMillis > 1000) {
                    tempMillis /= 10;
                    factor     *= 10;
                }

                nanos *= (1000000 / factor);
            } catch (Exception e) {
                throw Error.error(ErrorCode.X_22007, e.toString());
            }
        }

        return new TimestampData(millis / 1000, nanos, 0);
    }

    public static String toFormattedDate(Date date, String pattern,
                                         SimpleDateFormat format) {

        String javaPattern = HsqlDateTime.toJavaDatePattern(pattern);

        try {
            format.applyPattern(javaPattern);
        } catch (Exception e) {
            throw Error.error(ErrorCode.X_22511);
        }

        String result     = format.format(date);
        int    matchIndex = result.indexOf("*IY");

        if (matchIndex >= 0) {
            Calendar cal         = format.getCalendar();
            int      matchLength = 3;
            int      temp        = result.indexOf("*IYYY");

            if (temp >= 0) {
                matchLength = 5;
                matchIndex  = temp;
            }

            int year       = cal.get(Calendar.YEAR);
            int weekOfYear = cal.get(Calendar.WEEK_OF_YEAR);

            if (weekOfYear == 1 && cal.get(Calendar.DAY_OF_YEAR) > 360) {
                year++;
            } else if (weekOfYear > 51 && cal.get(Calendar.DAY_OF_YEAR) < 4) {
                year--;
            }

            String yearString = String.valueOf(year);

            if (matchLength == 3) {
                yearString = yearString.substring(yearString.length() - 2);
            }

            StringBuilder sb = new StringBuilder(result);

            sb.replace(matchIndex, matchIndex + matchLength, yearString);

            result = sb.toString();
        }

        matchIndex = result.indexOf("*WW");

        if (matchIndex >= 0) {
            Calendar cal         = format.getCalendar();
            int      matchLength = 3;
            int      dayOfYear   = cal.get(Calendar.DAY_OF_YEAR);
            int      weekOfYear  = ((dayOfYear - 1) / 7) + 1;
            String   week        = String.valueOf(weekOfYear);

            if (week.length() == 1) {
                week = "0" + week;
            }

            StringBuilder sb = new StringBuilder(result);

            sb.replace(matchIndex, matchIndex + matchLength, week);

            result = sb.toString();
        }

        matchIndex = result.indexOf("*IW");

        if (matchIndex >= 0) {
            Calendar cal         = format.getCalendar();
            int      matchLength = 3;
            int      weekOfYear  = cal.get(Calendar.WEEK_OF_YEAR);
            String   week        = String.valueOf(weekOfYear);

            if (week.length() == 1) {
                week = "0" + week;
            }

            StringBuilder sb = new StringBuilder(result);

            sb.replace(matchIndex, matchIndex + matchLength, week);

            result = sb.toString();
        }

        matchIndex = result.indexOf("*W");

        if (matchIndex >= 0) {
            Calendar      cal         = format.getCalendar();
            int           matchLength = 2;
            int           dayOfMonth  = cal.get(Calendar.DAY_OF_MONTH);
            int           weekOfMonth = ((dayOfMonth - 1) / 7) + 1;
            StringBuilder sb          = new StringBuilder(result);

            sb.replace(matchIndex, matchIndex + matchLength,
                       String.valueOf(weekOfMonth));

            result = sb.toString();
        }

        return result;
    }

    /**
     * Converts the given format into a pattern accepted by <code>java.text.SimpleDataFormat</code>
     *
     * @param format date format
     */
    public static String toJavaDatePattern(String format) {

        int           len = format.length();
        char          ch;
        StringBuilder sb               = new StringBuilder(len);
        Tokenizer     tokenizer        = new Tokenizer();
        int           limitQuotedToken = -1;

        for (int i = 0; i <= len; i++) {
            ch = (i == len) ? e
                            : format.charAt(i);

            if (tokenizer.isInQuotes()) {
                if (tokenizer.isQuoteChar(ch)) {
                    ch = '\'';
                } else if (ch == '\'') {

                    // double the single quote
                    sb.append(ch);
                }

                sb.append(ch);

                continue;
            }

            if (!tokenizer.next(ch, i)) {
                if (tokenizer.consumed) {
                    int    index = tokenizer.getLastMatch();
                    String s     = javaDateTokens[index];

                    // consecutive quoted tokens
                    if (s.startsWith("'") && s.endsWith("'")) {
                        if (limitQuotedToken == sb.length()) {
                            sb.setLength(sb.length() - 1);

                            s = s.substring(1);
                        }

                        limitQuotedToken = sb.length() + s.length();
                    }

                    sb.append(s);

                    i = tokenizer.matchOffset;
                } else {
                    if (tokenizer.isQuoteChar(ch)) {
                        ch = '\'';

                        sb.append(ch);
                    } else if (tokenizer.isLiteral(ch)) {
                        sb.append(ch);
                    } else if (ch == e) {

                        //
                    } else {
                        throw Error.error(ErrorCode.X_22007,
                                          format.substring(i));
                    }
                }

                tokenizer.reset();
            }
        }

        if (tokenizer.isInQuotes()) {
            throw Error.error(ErrorCode.X_22007);
        }

        String javaPattern = sb.toString();

        return javaPattern;
    }

    public static int toStandardIntervalPart(String format) {

        int       len = format.length();
        char      ch;
        Tokenizer tokenizer = new Tokenizer();

        for (int i = 0; i <= len; i++) {
            ch = (i == len) ? e
                            : format.charAt(i);

            if (!tokenizer.next(ch, i)) {
                int index = tokenizer.getLastMatch();

                if (index >= 0) {
                    return sqlIntervalCodes[index];
                }

                return -1;
            }
        }

        return -1;
    }

    /**
     * Timestamp String generator
     */
    public static class SystemTimeString {

        private java.util.Date date = new java.util.Date();
        private SimpleDateFormat dateFormat =
            new SimpleDateFormat(sdftsSysPattern);

        public SystemTimeString() {

            dateFormat.setCalendar(
                new GregorianCalendar(
                    TimeZone.getTimeZone("GMT"), defaultLocale));
            dateFormat.setLenient(false);
        }

        public synchronized String getTimestampString() {

            date.setTime(System.currentTimeMillis());

            return dateFormat.format(date);
        }
    }

    /**
     * This class can match 64 tokens at maximum.
     */
    static class Tokenizer {

        private int     lastMatched;
        private int     matchOffset;
        private int     offset;
        private long    state;
        private boolean consumed;
        private boolean isInQuotes;
        private boolean matched;

        //
        private final char    quoteChar;
        private final char[]  literalChars;
        private static char[] defaultLiterals = new char[] {
            ' ', ',', '-', '.', '/', ':', ';'
        };
        char[][]              tokens;

        public Tokenizer() {

            this.quoteChar    = '\"';
            this.literalChars = defaultLiterals;
            tokens            = dateTokens;

            reset();
        }

        /**
         * Resets for next reuse.
         *
         */
        public void reset() {

            lastMatched = -1;
            offset      = -1;
            state       = 0;
            consumed    = false;
            matched     = false;
        }

        /**
         * Returns the length of a token to match.
         */
        public int length() {
            return offset;
        }

        /**
         * Returns an index of the last matched token.
         */
        public int getLastMatch() {
            return lastMatched;
        }

        /**
         * Indicates whether the last character has been consumed by the matcher.
         */
        public boolean isConsumed() {
            return consumed;
        }

        /**
         * Indicates whether the last character has been matched by the matcher.
         */
        public boolean wasMatched() {
            return matched;
        }

        /**
         * Indicates if tokenizing a quoted string
         */
        public boolean isInQuotes() {
            return isInQuotes;
        }

        /**
         * returns true if character is the quote char and sets state
         */
        public boolean isQuoteChar(char ch) {

            if (quoteChar == ch) {
                isInQuotes = !isInQuotes;

                return true;
            }

            return false;
        }

        /**
         * Returns true if ch is in the list of literals
         */
        public boolean isLiteral(char ch) {
            return ArrayUtil.isInSortedArray(ch, literalChars);
        }

        /**
         * Checks whether the specified bit is not set.
         *
         * @param bit numbered from high bit
         */
        private boolean isZeroBit(int bit) {
            return (state & (1L << bit)) == 0;
        }

        /**
         * Sets the specified bit.
         * @param bit numbered from high bit
         */
        private void setBit(int bit) {
            state |= (1L << bit);
        }

        /**
         * Matches the specified character against tokens.
         *
         * @param ch character
         * @param position in the string
         */
        public boolean next(char ch, int position) {

            int index = ++offset;
            int len   = offset + 1;
            int left  = 0;

            matched = false;

            for (int i = tokens.length; --i >= 0; ) {
                if (isZeroBit(i)) {
                    if (tokens[i][index] == Character.toUpperCase(ch)) {
                        if (tokens[i].length == len) {
                            setBit(i);

                            lastMatched = i;
                            consumed    = true;
                            matched     = true;
                            matchOffset = position;
                        } else {
                            ++left;
                        }
                    } else {
                        setBit(i);
                    }
                }
            }

            return left > 0;
        }
    }
}
