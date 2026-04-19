package com.chatapp.message.enums;

/**
 * Loại tin nhắn trong conversation.
 * Lưu dưới dạng STRING trong DB (column `type`).
 *
 * TEXT   — tin nhắn văn bản thông thường
 * IMAGE  — tin nhắn có ảnh đính kèm
 * FILE   — tin nhắn có file đính kèm
 * SYSTEM — tin nhắn hệ thống (vd: "User A đã thêm User B vào nhóm")
 */
public enum MessageType {
    TEXT,
    IMAGE,
    FILE,
    SYSTEM
}
