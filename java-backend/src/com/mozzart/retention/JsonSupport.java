package com.mozzart.retention;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonSupport {
  private JsonSupport() {}

  public static String stringify(Object value) {
    StringBuilder builder = new StringBuilder();
    writeJson(builder, value);
    return builder.toString();
  }

  public static Object parse(String json) {
    return new Parser(json).parseValue();
  }

  private static void writeJson(StringBuilder builder, Object value) {
    if (value == null) {
      builder.append("null");
      return;
    }

    if (value instanceof String text) {
      builder.append('"');
      for (int index = 0; index < text.length(); index += 1) {
        char character = text.charAt(index);
        switch (character) {
          case '"' -> builder.append("\\\"");
          case '\\' -> builder.append("\\\\");
          case '\b' -> builder.append("\\b");
          case '\f' -> builder.append("\\f");
          case '\n' -> builder.append("\\n");
          case '\r' -> builder.append("\\r");
          case '\t' -> builder.append("\\t");
          default -> {
            if (character < 0x20) {
              builder.append(String.format("\\u%04x", (int) character));
            } else {
              builder.append(character);
            }
          }
        }
      }
      builder.append('"');
      return;
    }

    if (value instanceof Number || value instanceof Boolean) {
      builder.append(value);
      return;
    }

    if (value instanceof Map<?, ?> map) {
      builder.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          builder.append(',');
        }
        first = false;
        writeJson(builder, String.valueOf(entry.getKey()));
        builder.append(':');
        writeJson(builder, entry.getValue());
      }
      builder.append('}');
      return;
    }

    if (value instanceof Collection<?> collection) {
      builder.append('[');
      boolean first = true;
      for (Object item : collection) {
        if (!first) {
          builder.append(',');
        }
        first = false;
        writeJson(builder, item);
      }
      builder.append(']');
      return;
    }

    if (value instanceof Object[] array) {
      builder.append('[');
      for (int index = 0; index < array.length; index += 1) {
        if (index > 0) {
          builder.append(',');
        }
        writeJson(builder, array[index]);
      }
      builder.append(']');
      return;
    }

    writeJson(builder, String.valueOf(value));
  }

  private static final class Parser {
    private final String source;
    private int index;

    private Parser(String source) {
      this.source = source == null ? "" : source;
    }

    private Object parseValue() {
      skipWhitespace();
      if (index >= source.length()) {
        throw new IllegalArgumentException("Unexpected end of JSON input");
      }

      char character = source.charAt(index);
      return switch (character) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> parseTrue();
        case 'f' -> parseFalse();
        case 'n' -> parseNull();
        default -> parseNumber();
      };
    }

    private Map<String, Object> parseObject() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      index += 1;
      skipWhitespace();
      if (peek('}')) {
        index += 1;
        return result;
      }

      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        Object value = parseValue();
        result.put(key, value);
        skipWhitespace();
        if (peek('}')) {
          index += 1;
          return result;
        }
        expect(',');
      }
    }

    private List<Object> parseArray() {
      List<Object> result = new ArrayList<>();
      index += 1;
      skipWhitespace();
      if (peek(']')) {
        index += 1;
        return result;
      }

      while (true) {
        result.add(parseValue());
        skipWhitespace();
        if (peek(']')) {
          index += 1;
          return result;
        }
        expect(',');
      }
    }

    private String parseString() {
      expect('"');
      StringBuilder builder = new StringBuilder();

      while (index < source.length()) {
        char character = source.charAt(index++);
        if (character == '"') {
          return builder.toString();
        }
        if (character != '\\') {
          builder.append(character);
          continue;
        }
        if (index >= source.length()) {
          throw new IllegalArgumentException("Invalid escape sequence");
        }
        char escaped = source.charAt(index++);
        switch (escaped) {
          case '"', '\\', '/' -> builder.append(escaped);
          case 'b' -> builder.append('\b');
          case 'f' -> builder.append('\f');
          case 'n' -> builder.append('\n');
          case 'r' -> builder.append('\r');
          case 't' -> builder.append('\t');
          case 'u' -> {
            if (index + 4 > source.length()) {
              throw new IllegalArgumentException("Invalid unicode escape");
            }
            String hex = source.substring(index, index + 4);
            builder.append((char) Integer.parseInt(hex, 16));
            index += 4;
          }
          default -> throw new IllegalArgumentException("Unsupported escape sequence");
        }
      }

      throw new IllegalArgumentException("Unterminated string literal");
    }

    private Boolean parseTrue() {
      expectLiteral("true");
      return Boolean.TRUE;
    }

    private Boolean parseFalse() {
      expectLiteral("false");
      return Boolean.FALSE;
    }

    private Object parseNull() {
      expectLiteral("null");
      return null;
    }

    private Number parseNumber() {
      int start = index;
      if (peek('-')) {
        index += 1;
      }
      consumeDigits();
      boolean decimal = false;
      if (peek('.')) {
        decimal = true;
        index += 1;
        consumeDigits();
      }
      if (peek('e') || peek('E')) {
        decimal = true;
        index += 1;
        if (peek('+') || peek('-')) {
          index += 1;
        }
        consumeDigits();
      }

      String token = source.substring(start, index);
      try {
        if (decimal) {
          return Double.parseDouble(token);
        }
        return Long.parseLong(token);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("Invalid number: " + token, error);
      }
    }

    private void consumeDigits() {
      int digits = 0;
      while (index < source.length() && Character.isDigit(source.charAt(index))) {
        index += 1;
        digits += 1;
      }
      if (digits == 0) {
        throw new IllegalArgumentException("Expected digit");
      }
    }

    private void expect(char expected) {
      skipWhitespace();
      if (index >= source.length() || source.charAt(index) != expected) {
        throw new IllegalArgumentException("Expected `" + expected + "`");
      }
      index += 1;
    }

    private void expectLiteral(String literal) {
      if (!source.startsWith(literal, index)) {
        throw new IllegalArgumentException("Expected `" + literal + "`");
      }
      index += literal.length();
    }

    private boolean peek(char expected) {
      return index < source.length() && source.charAt(index) == expected;
    }

    private void skipWhitespace() {
      while (index < source.length()) {
        char character = source.charAt(index);
        if (!Character.isWhitespace(character)) {
          return;
        }
        index += 1;
      }
    }
  }
}
