package com.example.pakredirect;

/** Normalizes non-ASCII Lua identifiers while preserving strings and comments. */
final class LuaSourceCompat {
    private LuaSourceCompat() { }

    static String normalizeUnicodeIdentifiers(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int length = source.length();
        for (int i = 0; i < length;) {
            char ch = source.charAt(i);
            if (ch == '\'' || ch == '"') {
                i = copyQuoted(source, i, out, ch);
                continue;
            }
            if (ch == '-' && i + 1 < length && source.charAt(i + 1) == '-') {
                int bracketLevel = longBracketLevel(source, i + 2);
                if (bracketLevel >= 0) i = copyLongBracket(source, i + 2, bracketLevel, out, "--");
                else i = copyLine(source, i, out);
                continue;
            }
            int bracketLevel = ch == '[' ? longBracketLevel(source, i) : -1;
            if (bracketLevel >= 0) {
                i = copyLongBracket(source, i, bracketLevel, out, "");
                continue;
            }

            int codePoint = source.codePointAt(i);
            if (isIdentifierStart(codePoint)) {
                int start = i;
                boolean hasUnicode = codePoint > 0x7f;
                i += Character.charCount(codePoint);
                while (i < length) {
                    int next = source.codePointAt(i);
                    if (!isIdentifierPart(next)) break;
                    hasUnicode |= next > 0x7f;
                    i += Character.charCount(next);
                }
                String identifier = source.substring(start, i);
                out.append(hasUnicode ? asciiIdentifier(identifier) : identifier);
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentifierStart(int codePoint) {
        return codePoint == '_' || codePoint < 128 && Character.isLetter(codePoint)
                || codePoint >= 128 && Character.isUnicodeIdentifierStart(codePoint);
    }

    private static boolean isIdentifierPart(int codePoint) {
        return codePoint == '_' || codePoint < 128 && Character.isLetterOrDigit(codePoint)
                || codePoint >= 128 && Character.isUnicodeIdentifierPart(codePoint);
    }

    private static String asciiIdentifier(String identifier) {
        StringBuilder normalized = new StringBuilder("__rylux_u");
        identifier.codePoints().forEach(codePoint -> normalized.append(Integer.toHexString(codePoint)).append('_'));
        return normalized.toString();
    }

    private static int copyQuoted(String source, int start, StringBuilder out, char quote) {
        int i = start;
        out.append(source.charAt(i++));
        while (i < source.length()) {
            char ch = source.charAt(i++);
            out.append(ch);
            if (ch == '\\' && i < source.length()) out.append(source.charAt(i++));
            else if (ch == quote) break;
        }
        return i;
    }

    private static int copyLine(String source, int start, StringBuilder out) {
        int i = start;
        while (i < source.length()) {
            char ch = source.charAt(i++);
            out.append(ch);
            if (ch == '\n') break;
        }
        return i;
    }

    /** Returns the number of '=' characters in a Lua long-bracket opener, or -1. */
    private static int longBracketLevel(String source, int start) {
        if (start >= source.length() || source.charAt(start) != '[') return -1;
        int i = start + 1;
        while (i < source.length() && source.charAt(i) == '=') i++;
        return i < source.length() && source.charAt(i) == '[' ? i - start - 1 : -1;
    }

    private static int copyLongBracket(String source, int start, int level, StringBuilder out, String prefix) {
        out.append(prefix);
        int openerEnd = start + level + 2;
        out.append(source, start, openerEnd);
        String closer = "]" + "=".repeat(level) + "]";
        int close = source.indexOf(closer, openerEnd);
        if (close < 0) {
            out.append(source, openerEnd, source.length());
            return source.length();
        }
        int end = close + closer.length();
        out.append(source, openerEnd, end);
        return end;
    }
}
