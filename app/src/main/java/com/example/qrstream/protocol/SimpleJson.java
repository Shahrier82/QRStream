package com.example.qrstream.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal parser for one flat JSON object whose values are strings, integers, booleans or null.
 *
 * <p>The META payload is flat, and {@code org.json} is not available in plain JVM unit
 * tests, so the protocol package parses it itself. Nested objects and arrays are rejected.</p>
 */
final class SimpleJson {

    private static final int HEX_RADIX = 16;
    private static final int UNICODE_ESCAPE_LENGTH = 4;
    private static final char FIRST_PRINTABLE = 0x20;

    private final String text;
    private int pos;

    private SimpleJson(String text) {
        this.text = text;
    }

    /**
     * Parses a flat JSON object.
     *
     * @param json JSON text
     * @return map of key to String, Long, Boolean or null
     * @throws FrameFormatException if the text is not a flat JSON object
     */
    static Map<String, Object> parseObject(String json) throws FrameFormatException {
        SimpleJson parser = new SimpleJson(json);
        Map<String, Object> result = parser.readObject();
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw new FrameFormatException("Unexpected text after JSON object");
        }
        return result;
    }

    /**
     * Quotes and escapes a string as a JSON string literal.
     *
     * @param value text to quote, not null
     * @return the literal including the surrounding quotes
     */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < FIRST_PRINTABLE) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private Map<String, Object> readObject() throws FrameFormatException {
        Map<String, Object> map = new HashMap<>();
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new FrameFormatException("Expected ',' or '}' in JSON");
            }
        }
    }

    private Object readValue() throws FrameFormatException {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return readInteger();
        }
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new FrameFormatException("Unsupported JSON value at " + pos);
    }

    private Long readInteger() throws FrameFormatException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
            pos++;
        }
        if (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                throw new FrameFormatException("Only integer numbers are supported");
            }
        }
        try {
            return Long.valueOf(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new FrameFormatException("Bad number in JSON", e);
        }
    }

    private String readString() throws FrameFormatException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"':
                case '\\':
                case '/':
                    sb.append(esc);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + UNICODE_ESCAPE_LENGTH > text.length()) {
                        throw new FrameFormatException("Truncated unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(
                                text.substring(pos, pos + UNICODE_ESCAPE_LENGTH), HEX_RADIX));
                    } catch (NumberFormatException e) {
                        throw new FrameFormatException("Bad unicode escape", e);
                    }
                    pos += UNICODE_ESCAPE_LENGTH;
                    break;
                default:
                    throw new FrameFormatException("Bad escape in JSON string");
            }
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() throws FrameFormatException {
        if (pos >= text.length()) {
            throw new FrameFormatException("Unexpected end of JSON");
        }
        return text.charAt(pos);
    }

    private char next() throws FrameFormatException {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) throws FrameFormatException {
        if (next() != expected) {
            throw new FrameFormatException("Expected '" + expected + "' in JSON");
        }
    }
}
