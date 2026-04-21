package com.chatapp.conversation;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests cho W7-D2: Member Management (5 endpoints).
 *
 * addMembers (6): A01-A06
 * removeMember/kick (5): K01-K05
 * leaveGroup (6): L01-L06
 * changeRole (5): R01-R05
 * transferOwner (4): T01-T04
 *
 * Tổng 26 tests, đáp ứng yêu cầu ≥ 20.
 *
 * Verify broadcast qua @MockBean SimpMessagingTemplate (giống GroupConversationTest).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthProviderRepository userAuthProviderRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository memberRepository;

    @MockBean private StringRedisTemplate redisTemplate;
    @MockBean private FirebaseAuth firebaseAuth;
    @MockBean private SimpMessagingTemplate messagingTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        memberRepository.deleteAll();
        conversationRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(900L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private record User(String token, UUID userId) {}

    private User register(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Pass123!A","fullName":"Test %s"}
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return new User(
                tree.get("accessToken").asText(),
                UUID.fromString(tree.get("user").get("id").asText())
        );
    }

    private UUID createGroup(String ownerToken, String name, List<UUID> memberIds) throws Exception {
        String idsJson = memberIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"%s","memberIds":[%s]}
                                """.formatted(name, idsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createDirect(String token, UUID targetId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ONE_ON_ONE","memberIds":["%s"]}
                                """.formatted(targetId)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void promoteToAdmin(UUID convId, UUID userId) {
        ConversationMember m = memberRepository.findByConversation_IdAndUser_Id(convId, userId).orElseThrow();
        m.setRole(MemberRole.ADMIN);
        memberRepository.saveAndFlush(m);
    }

    // ==========================================================================
    // A01-A06: POST /api/conversations/{id}/members
    // ==========================================================================

    @Test
    void A01_addMembers_ownerAddsTwoValidUsers_returns201() throws Exception {
        User a = register("a01a@test.com", "a01a");
        User b = register("a01b@test.com", "a01b");
        User c = register("a01c@test.com", "a01c");
        UUID convId = createGroup(a.token, "A01 Group",
                List.of(b.userId, c.userId));

        User d = register("a01d@test.com", "a01d");
        User e = register("a01e@test.com", "a01e");

        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s","%s"]}
                                """.formatted(d.userId, e.userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.added[0].role").value("MEMBER"));

        // Verify DB
        assertEquals(5, memberRepository.countByConversation_Id(convId));

        // Verify broadcast MEMBER_ADDED fire (per user) + /user/.../queue/conv-added
        verify(messagingTemplate, atLeast(2)).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(d.userId.toString()), eq("/queue/conv-added"), any(Object.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(e.userId.toString()), eq("/queue/conv-added"), any(Object.class));
    }

    @Test
    void A02_addMembers_memberTries_returns403InsufficientPermission() throws Exception {
        User a = register("a02a@test.com", "a02a");
        User b = register("a02b@test.com", "a02b");
        User c = register("a02c@test.com", "a02c");
        UUID convId = createGroup(a.token, "A02 Group", List.of(b.userId, c.userId));

        User d = register("a02d@test.com", "a02d");

        // B is MEMBER — not allowed to add
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + b.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s"]}
                                """.formatted(d.userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }

    @Test
    void A03_addMembers_groupAt50_returns409MemberLimitExceeded() throws Exception {
        User a = register("a03a@test.com", "a03a");
        // Build a group of 50 members total. Caller + 49 others.
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 49; i++) {
            User u = register("a03_" + i + "@test.com", "a03_" + i);
            if (i > 0) ids.append(",");
            ids.append("\"").append(u.userId).append("\"");
        }
        MvcResult createRes = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Full Group","memberIds":[%s]}
                                """.formatted(ids)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID convId = UUID.fromString(
                objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText());

        assertEquals(50, memberRepository.countByConversation_Id(convId));

        // Try to add 1 more → MEMBER_LIMIT_EXCEEDED
        User extra = register("a03extra@test.com", "a03extra");
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s"]}
                                """.formatted(extra.userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MEMBER_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.currentCount").value(50))
                .andExpect(jsonPath("$.details.limit").value(50));
    }

    @Test
    void A04_addMembers_batchMixOfExistingAndValid_returnsPartialSuccess() throws Exception {
        User a = register("a04a@test.com", "a04a");
        User b = register("a04b@test.com", "a04b");
        User c = register("a04c@test.com", "a04c");
        UUID convId = createGroup(a.token, "A04 Group", List.of(b.userId, c.userId));

        User d = register("a04d@test.com", "a04d"); // new
        // Batch: [d (valid), b (already member)]
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s","%s"]}
                                """.formatted(d.userId, b.userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_MEMBER"))
                .andExpect(jsonPath("$.skipped[0].userId").value(b.userId.toString()));
    }

    @Test
    void A05_addMembers_userIdNotExist_skippedReasonUserNotFound() throws Exception {
        User a = register("a05a@test.com", "a05a");
        User b = register("a05b@test.com", "a05b");
        User c = register("a05c@test.com", "a05c");
        UUID convId = createGroup(a.token, "A05 Group", List.of(b.userId, c.userId));

        UUID fakeId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        User valid = register("a05v@test.com", "a05v");

        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s","%s"]}
                                """.formatted(fakeId, valid.userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].reason").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.skipped[0].userId").value(fakeId.toString()));
    }

    @Test
    void A06_addMembers_tooManyUserIds_returns400() throws Exception {
        User a = register("a06a@test.com", "a06a");
        User b = register("a06b@test.com", "a06b");
        User c = register("a06c@test.com", "a06c");
        UUID convId = createGroup(a.token, "A06 Group", List.of(b.userId, c.userId));

        // Build 11 userIds
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            ids.append("\"").append(UUID.randomUUID()).append("\"");
            if (i < 10) ids.append(",");
        }
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%s]}
                                """.formatted(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // ==========================================================================
    // K01-K05: DELETE /api/conversations/{id}/members/{userId}
    // ==========================================================================

    @Test
    void K01_removeMember_ownerKicksMember_returns204AndBroadcasts() throws Exception {
        User a = register("k01a@test.com", "k01a");
        User b = register("k01b@test.com", "k01b");
        User c = register("k01c@test.com", "k01c");
        UUID convId = createGroup(a.token, "K01 Group", List.of(b.userId, c.userId));

        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + b.userId)
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isNoContent());

        // Verify DB
        assertFalse(memberRepository.existsByConversation_IdAndUser_Id(convId, b.userId));

        // Verify topic broadcast
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
        // Verify user-queue for kicked user
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(b.userId.toString()), eq("/queue/conv-removed"), any(Object.class));
    }

    @Test
    void K02_removeMember_adminKicksMember_returns204() throws Exception {
        User a = register("k02a@test.com", "k02a");
        User b = register("k02b@test.com", "k02b");
        User c = register("k02c@test.com", "k02c");
        UUID convId = createGroup(a.token, "K02 Group", List.of(b.userId, c.userId));

        promoteToAdmin(convId, b.userId);

        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + c.userId)
                        .header("Authorization", "Bearer " + b.token))
                .andExpect(status().isNoContent());

        assertFalse(memberRepository.existsByConversation_IdAndUser_Id(convId, c.userId));
    }

    @Test
    void K03_removeMember_adminKicksAdmin_returns403() throws Exception {
        User a = register("k03a@test.com", "k03a");
        User b = register("k03b@test.com", "k03b");
        User c = register("k03c@test.com", "k03c");
        UUID convId = createGroup(a.token, "K03 Group", List.of(b.userId, c.userId));

        promoteToAdmin(convId, b.userId);
        promoteToAdmin(convId, c.userId);

        // B (ADMIN) tries to kick C (ADMIN) → 403
        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + c.userId)
                        .header("Authorization", "Bearer " + b.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }

    @Test
    void K04_removeMember_memberKicksAnyone_returns403() throws Exception {
        User a = register("k04a@test.com", "k04a");
        User b = register("k04b@test.com", "k04b");
        User c = register("k04c@test.com", "k04c");
        UUID convId = createGroup(a.token, "K04 Group", List.of(b.userId, c.userId));

        // B is MEMBER, tries kick C → 403
        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + c.userId)
                        .header("Authorization", "Bearer " + b.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }

    @Test
    void K05_removeMember_selfKick_returns400CannotKickSelf() throws Exception {
        User a = register("k05a@test.com", "k05a");
        User b = register("k05b@test.com", "k05b");
        User c = register("k05c@test.com", "k05c");
        UUID convId = createGroup(a.token, "K05 Group", List.of(b.userId, c.userId));

        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + a.userId)
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_KICK_SELF"));
    }

    // ==========================================================================
    // L01-L06: POST /api/conversations/{id}/leave
    // ==========================================================================

    @Test
    void L01_leaveGroup_memberLeaves_returns204BroadcastsLeft() throws Exception {
        User a = register("l01a@test.com", "l01a");
        User b = register("l01b@test.com", "l01b");
        User c = register("l01c@test.com", "l01c");
        UUID convId = createGroup(a.token, "L01 Group", List.of(b.userId, c.userId));

        // B leaves
        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + b.token))
                .andExpect(status().isNoContent());

        assertFalse(memberRepository.existsByConversation_IdAndUser_Id(convId, b.userId));

        // Verify MEMBER_REMOVED broadcast — topic with reason=LEFT, removedBy=null
        // Verify NO user-queue conv-removed (LEFT doesn't fire user queue)
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(b.userId.toString()), eq("/queue/conv-removed"), any(Object.class));
    }

    @Test
    void L02_leaveGroup_adminLeaves_returns204() throws Exception {
        User a = register("l02a@test.com", "l02a");
        User b = register("l02b@test.com", "l02b");
        User c = register("l02c@test.com", "l02c");
        UUID convId = createGroup(a.token, "L02 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + b.token))
                .andExpect(status().isNoContent());

        assertFalse(memberRepository.existsByConversation_IdAndUser_Id(convId, b.userId));
    }

    @Test
    void L03_leaveGroup_ownerLeavesWithAdmin_autoTransfersToAdmin() throws Exception {
        User a = register("l03a@test.com", "l03a");
        User b = register("l03b@test.com", "l03b");
        User c = register("l03c@test.com", "l03c");
        UUID convId = createGroup(a.token, "L03 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        // A (OWNER) leaves → B (ADMIN, oldest promoted) becomes OWNER
        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isNoContent());

        // Verify B is now OWNER
        ConversationMember bMember = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.OWNER, bMember.getRole());

        // Verify conversations.owner_id updated
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertEquals(b.userId, conv.getOwnerId());

        // Verify A removed
        assertFalse(memberRepository.existsByConversation_IdAndUser_Id(convId, a.userId));

        // Verify at least 2 broadcasts fire (OWNER_TRANSFERRED then MEMBER_REMOVED)
        verify(messagingTemplate, atLeast(2)).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
    }

    @Test
    void L04_leaveGroup_ownerLeavesWithOnlyMembers_transfersToOldestMember() throws Exception {
        User a = register("l04a@test.com", "l04a");
        User b = register("l04b@test.com", "l04b");
        User c = register("l04c@test.com", "l04c");
        UUID convId = createGroup(a.token, "L04 Group", List.of(b.userId, c.userId));
        // No promotion — b and c are MEMBER. B joined first (order in createGroup).

        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isNoContent());

        // Verify one of B/C becomes OWNER (oldest-MEMBER first). Order by joinedAt insertion — same second, so accept either.
        ConversationMember bMember = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElse(null);
        ConversationMember cMember = memberRepository.findByConversation_IdAndUser_Id(convId, c.userId).orElse(null);
        assertNotNull(bMember);
        assertNotNull(cMember);
        boolean eitherOwner = bMember.getRole() == MemberRole.OWNER || cMember.getRole() == MemberRole.OWNER;
        assertTrue(eitherOwner, "One of remaining members must be OWNER");
    }

    @Test
    void L05_leaveGroup_ownerAlone_returns400CannotLeaveEmpty() throws Exception {
        User a = register("l05a@test.com", "l05a");
        User b = register("l05b@test.com", "l05b");
        User c = register("l05c@test.com", "l05c");
        UUID convId = createGroup(a.token, "L05 Group", List.of(b.userId, c.userId));

        // Remove b and c directly so A is alone
        ConversationMember bMem = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        ConversationMember cMem = memberRepository.findByConversation_IdAndUser_Id(convId, c.userId).orElseThrow();
        memberRepository.delete(bMem);
        memberRepository.delete(cMem);

        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_LEAVE_EMPTY_GROUP"));

        // A still OWNER
        ConversationMember aMem = memberRepository.findByConversation_IdAndUser_Id(convId, a.userId).orElseThrow();
        assertEquals(MemberRole.OWNER, aMem.getRole());
    }

    @Test
    void L06_leaveGroup_autoTransferAdminPriorityOverMember() throws Exception {
        // Create group with owner A + member B. Later add ADMIN D and MEMBER E (D younger than E).
        // When A leaves, priority should pick ADMIN D even though B and E are MEMBERs and B is oldest.
        User a = register("l06a@test.com", "l06a");
        User b = register("l06b@test.com", "l06b");
        User c = register("l06c@test.com", "l06c");
        UUID convId = createGroup(a.token, "L06 Group", List.of(b.userId, c.userId));

        // Promote C to ADMIN (b stays MEMBER, and B is older joinedAt than C was — actually same timestamp).
        // To have clear priority test: promote C to ADMIN.
        promoteToAdmin(convId, c.userId);

        // Now A leaves → should transfer to C (ADMIN) not B (MEMBER).
        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isNoContent());

        ConversationMember cMember = memberRepository.findByConversation_IdAndUser_Id(convId, c.userId).orElseThrow();
        ConversationMember bMember = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.OWNER, cMember.getRole(),
                "ADMIN C must be promoted to OWNER (priority over MEMBER)");
        assertEquals(MemberRole.MEMBER, bMember.getRole(), "MEMBER B stays MEMBER");
    }

    // ==========================================================================
    // R01-R05: PATCH /api/conversations/{id}/members/{userId}/role
    // ==========================================================================

    @Test
    void R01_changeRole_ownerPromotesMemberToAdmin_returns200AndBroadcasts() throws Exception {
        User a = register("r01a@test.com", "r01a");
        User b = register("r01b@test.com", "r01b");
        User c = register("r01c@test.com", "r01c");
        UUID convId = createGroup(a.token, "R01 Group", List.of(b.userId, c.userId));

        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId + "/role")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.userId").value(b.userId.toString()))
                .andExpect(jsonPath("$.changedBy.userId").value(a.userId.toString()));

        // Verify DB state
        ConversationMember bMem = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.ADMIN, bMem.getRole());

        // Verify broadcast
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
    }

    @Test
    void R02_changeRole_ownerDemotesAdminToMember_returns200AndBroadcasts() throws Exception {
        User a = register("r02a@test.com", "r02a");
        User b = register("r02b@test.com", "r02b");
        User c = register("r02c@test.com", "r02c");
        UUID convId = createGroup(a.token, "R02 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId + "/role")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        ConversationMember bMem = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.MEMBER, bMem.getRole());
    }

    @Test
    void R03_changeRole_noOp_returns200NoBroadcast() throws Exception {
        User a = register("r03a@test.com", "r03a");
        User b = register("r03b@test.com", "r03b");
        User c = register("r03c@test.com", "r03c");
        UUID convId = createGroup(a.token, "R03 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        reset(messagingTemplate); // clear prior broadcast invocations (from createGroup)

        // Set ADMIN again — no-op
        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId + "/role")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Verify NO broadcast fire for this no-op
        verify(messagingTemplate, never()).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));
    }

    @Test
    void R04_changeRole_adminTries_returns403() throws Exception {
        User a = register("r04a@test.com", "r04a");
        User b = register("r04b@test.com", "r04b");
        User c = register("r04c@test.com", "r04c");
        UUID convId = createGroup(a.token, "R04 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        // B (ADMIN) tries to promote C — not allowed
        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + c.userId + "/role")
                        .header("Authorization", "Bearer " + b.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }

    @Test
    void R05_changeRole_ownerRoleInBody_returns400InvalidRole() throws Exception {
        User a = register("r05a@test.com", "r05a");
        User b = register("r05b@test.com", "r05b");
        User c = register("r05c@test.com", "r05c");
        UUID convId = createGroup(a.token, "R05 Group", List.of(b.userId, c.userId));

        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId + "/role")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE"));
    }

    // ==========================================================================
    // T01-T04: POST /api/conversations/{id}/transfer-owner
    // ==========================================================================

    @Test
    void T01_transferOwner_ownerToMember_swapsRoles() throws Exception {
        User a = register("t01a@test.com", "t01a");
        User b = register("t01b@test.com", "t01b");
        User c = register("t01c@test.com", "t01c");
        UUID convId = createGroup(a.token, "T01 Group", List.of(b.userId, c.userId));

        MvcResult res = mockMvc.perform(post("/api/conversations/" + convId + "/transfer-owner")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":"%s"}
                                """.formatted(b.userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousOwner.userId").value(a.userId.toString()))
                .andExpect(jsonPath("$.previousOwner.newRole").value("ADMIN"))
                .andExpect(jsonPath("$.newOwner.userId").value(b.userId.toString()))
                .andReturn();

        // Verify DB state
        ConversationMember aMem = memberRepository.findByConversation_IdAndUser_Id(convId, a.userId).orElseThrow();
        ConversationMember bMem = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.ADMIN, aMem.getRole(), "Old owner → ADMIN (not MEMBER)");
        assertEquals(MemberRole.OWNER, bMem.getRole());

        // Verify conversations.owner_id updated
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertEquals(b.userId, conv.getOwnerId());

        // Verify OWNER_TRANSFERRED broadcast fire
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId), any(Object.class));

        // Sanity: response body parseable
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        assertNotNull(body.get("previousOwner").get("username"));
    }

    @Test
    void T02_transferOwner_ownerToAdmin_swapsRoles() throws Exception {
        User a = register("t02a@test.com", "t02a");
        User b = register("t02b@test.com", "t02b");
        User c = register("t02c@test.com", "t02c");
        UUID convId = createGroup(a.token, "T02 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        mockMvc.perform(post("/api/conversations/" + convId + "/transfer-owner")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":"%s"}
                                """.formatted(b.userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOwner.userId").value(b.userId.toString()));

        ConversationMember aMem = memberRepository.findByConversation_IdAndUser_Id(convId, a.userId).orElseThrow();
        ConversationMember bMem = memberRepository.findByConversation_IdAndUser_Id(convId, b.userId).orElseThrow();
        assertEquals(MemberRole.ADMIN, aMem.getRole());
        assertEquals(MemberRole.OWNER, bMem.getRole());
    }

    @Test
    void T03_transferOwner_selfTarget_returns400CannotTransferToSelf() throws Exception {
        User a = register("t03a@test.com", "t03a");
        User b = register("t03b@test.com", "t03b");
        User c = register("t03c@test.com", "t03c");
        UUID convId = createGroup(a.token, "T03 Group", List.of(b.userId, c.userId));

        mockMvc.perform(post("/api/conversations/" + convId + "/transfer-owner")
                        .header("Authorization", "Bearer " + a.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":"%s"}
                                """.formatted(a.userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_TRANSFER_TO_SELF"));
    }

    @Test
    void T04_transferOwner_adminTries_returns403() throws Exception {
        User a = register("t04a@test.com", "t04a");
        User b = register("t04b@test.com", "t04b");
        User c = register("t04c@test.com", "t04c");
        UUID convId = createGroup(a.token, "T04 Group", List.of(b.userId, c.userId));
        promoteToAdmin(convId, b.userId);

        // B (ADMIN) tries transfer from A to C — not OWNER → 403
        mockMvc.perform(post("/api/conversations/" + convId + "/transfer-owner")
                        .header("Authorization", "Bearer " + b.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":"%s"}
                                """.formatted(c.userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }
}
