package com.chatapp.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response 201 Created cho POST /api/conversations/{id}/members (W7-D2).
 *
 * Partial-success shape — contract v1.1.0-w7:
 *  - added: users thực sự insert (có thể rỗng).
 *  - skipped: users KHÔNG insert kèm lý do (có thể rỗng). LUÔN non-null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AddMembersResponse(
        List<MemberDto> added,
        List<SkippedMemberDto> skipped
) {}
