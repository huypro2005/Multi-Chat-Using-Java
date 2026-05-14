package com.chatapp.reaction.service;

import com.chatapp.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Validates emoji strings for reactions (W8-D1).
 *
 * Validation order (rẻ trước đắt):
 * 1. Null / empty / whitespace-only check.
 * 2. Byte-length check: > 20 bytes UTF-8 → REJECT (DB VARCHAR(20) constraint + spec).
 *    NOTE: Byte-length check TRƯỚC regex — tránh regex bypass với compound emoji dài.
 *    Scotland flag (🏴󠁧󠁢󠁳󠁣󠁴󠁿 = 28 bytes UTF-8) → REJECT ở step 2 trước khi regex.
 * 3. Regex pattern: phải match unicode emoji range.
 *    Rejects plain ASCII text "hello", numbers, punctuation.
 *
 * Pattern covers:
 *   - Miscellaneous Symbols (U+2600–U+27BF): ☀, ❤, etc.
 *   - Emoticons / Supplemental Symbols (U+1F000–U+1FFFF): 😀, 🎉, 👍, etc.
 *   - Variation selectors (U+FE00–U+FEFF): emoji presentation modifier.
 *   - ZWJ (U+200D): Zero Width Joiner cho family emoji.
 *   - Combining Enclosing Keycap (U+20E3): keycap emoji 1️⃣.
 *   - Skin tone modifiers (U+1F3FB–U+1F3FF): 👍🏽.
 *   - Tags (U+E0020–U+E007F): PARTIAL — allow tag blocks in range (flag sequences).
 *     However byte-length check rejects full flag-tag sequences > 20 bytes.
 *
 * Error code: REACTION_INVALID_EMOJI (contract v1.5.0-w8-reactions).
 */
@Component
public class EmojiValidator {

    private static final int MAX_EMOJI_BYTES = 20;

    /**
     * Regex pattern cho emoji Unicode.
     * Covers basic emoji, ZWJ sequences, skin-tone modifiers, variation selectors.
     * Intentionally rejects: plain ASCII text, numbers, punctuation.
     */
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "^[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
            "\\x{FE00}-\\x{FEFF}\\x{200D}\\x{20E3}" +
            "\\x{1F3FB}-\\x{1F3FF}\\x{E0020}-\\x{E007F}" +
            "\\x{2300}-\\x{23FF}\\x{2400}-\\x{24FF}\\x{25A0}-\\x{25FF}" +
            "\\x{2100}-\\x{214F}\\x{2190}-\\x{21FF}]+$",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * Validate emoji string.
     *
     * @param emoji emoji string từ client payload
     * @throws AppException với code REACTION_INVALID_EMOJI nếu invalid
     */
    public void validate(String emoji) {
        // Step 1: Null / empty / whitespace-only
        if (emoji == null || emoji.isEmpty() || emoji.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "REACTION_INVALID_EMOJI",
                    "Biểu tượng cảm xúc không được trống");
        }

        // Step 2: Byte-length check TRƯỚC regex (tránh regex bypass)
        // VARCHAR(20) constraint ở DB — reject trước để không trigger DB error
        int byteLen = emoji.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen > MAX_EMOJI_BYTES) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "REACTION_INVALID_EMOJI",
                    "Biểu tượng cảm xúc quá dài (tối đa 20 bytes UTF-8)");
        }

        // Step 3: Pattern match
        if (!EMOJI_PATTERN.matcher(emoji).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "REACTION_INVALID_EMOJI",
                    "Biểu tượng cảm xúc không hợp lệ (phải là emoji Unicode)");
        }
    }
}
