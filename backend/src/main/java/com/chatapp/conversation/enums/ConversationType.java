package com.chatapp.conversation.enums;

/**
 * Loại cuộc trò chuyện.
 * Lưu dưới dạng string trong DB (EnumType.STRING).
 * ONE_ON_ONE: chat trực tiếp giữa 2 người.
 * GROUP: nhóm từ 2 người trở lên, có tên và avatar.
 */
public enum ConversationType {
    ONE_ON_ONE,
    GROUP
}
