package com.chatapp.message;

import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.DeleteMessagePayload;
import com.chatapp.message.dto.EditMessagePayload;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.MessageService;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Comparator;
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
 * Integration tests for W7-D4: SYSTEM messages.
 *
 * SM-01: createGroup → 1 SYSTEM GROUP_CREATED with actorId + actorName
 * SM-02: addMembers 3 unique users → 3 SYSTEM MEMBER_ADDED
 * SM-03: addMembers with 2 ALREADY_MEMBER skipped → only 1 SYSTEM (for added)
 * SM-04: removeMember → 1 SYSTEM MEMBER_REMOVED
 * SM-05: leaveGroup MEMBER → 1 SYSTEM MEMBER_LEFT
 * SM-06: leaveGroup OWNER with ADMIN → 2 SYSTEM: OWNER_TRANSFERRED (index 0) before MEMBER_LEFT (index 1)
 * SM-07: changeRole MEMBER→ADMIN → SYSTEM ROLE_PROMOTED
 * SM-08: changeRole ADMIN→MEMBER → SYSTEM ROLE_DEMOTED
 * SM-09: transferOwner → SYSTEM OWNER_TRANSFERRED with autoTransferred=false
 * SM-10: updateGroupInfo rename → SYSTEM GROUP_RENAMED with oldValue + newValue
 * SM-11: updateGroupInfo avatar only → 0 SYSTEM messages of type GROUP_RENAMED
 * SM-12: MessageService.editViaStomp SYSTEM message → SYSTEM_MESSAGE_NOT_EDITABLE (403)
 * SM-13: MessageService.deleteViaStomp SYSTEM message → SYSTEM_MESSAGE_NOT_DELETABLE (403)
 *
 * Total: 13 new tests.
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
class SystemMessageTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthProviderRepository userAuthProviderRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository memberRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageService messageService;

    @MockBean private StringRedisTemplate redisTemplate;
    @MockBean private FirebaseAuth firebaseAuth;
    @MockBean private SimpMessagingTemplate messagingTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messageRepository.deleteAll();
        memberRepository.deleteAll();
        conversationRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(valueOps.setIfAbsent(anyString(), eq("PENDING"), any(java.time.Duration.class))).thenReturn(true);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(900L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record RegUser(String token, UUID userId, String fullName) {}

    private RegUser register(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Pass123!A","fullName":"Full %s"}
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return new RegUser(
                tree.get("accessToken").asText(),
                UUID.fromString(tree.get("user").get("id").asText()),
                tree.get("user").get("fullName").asText()
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

    private void promoteToAdmin(UUID convId, UUID userId) {
        ConversationMember m = memberRepository.findByConversation_IdAndUser_Id(convId, userId).orElseThrow();
        m.setRole(MemberRole.ADMIN);
        memberRepository.saveAndFlush(m);
    }

    /** Returns all SYSTEM messages for convId sorted by createdAt ASC */
    private List<Message> getSystemMessages(UUID convId) {
        return messageRepository.findAll().stream()
                .filter(m -> convId.equals(m.getConversation().getId()))
                .filter(m -> MessageType.SYSTEM == m.getType())
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .toList();
    }

    // =========================================================================
    // SM-01: createGroup → 1 SYSTEM GROUP_CREATED
    // =========================================================================

    @Test
    void SM01_createGroup_generates_GROUP_CREATED_system_message() throws Exception {
        RegUser owner = register("sm01owner@test.com", "sm01owner");
        RegUser b = register("sm01b@test.com", "sm01b");
        RegUser c = register("sm01c@test.com", "sm01c");

        UUID convId = createGroup(owner.token(), "SM01 Group", List.of(b.userId(), c.userId()));

        List<Message> sysMsgs = getSystemMessages(convId);
        assertEquals(1, sysMsgs.size(), "Expected exactly 1 SYSTEM message for GROUP_CREATED");

        Message msg = sysMsgs.get(0);
        assertEquals("GROUP_CREATED", msg.getSystemEventType());
        assertEquals(MessageType.SYSTEM, msg.getType());
        assertNull(msg.getSender(), "SYSTEM messages must have null sender");
        assertEquals("", msg.getContent(), "SYSTEM messages must have empty string content");
        assertNotNull(msg.getSystemMetadata(), "systemMetadata must not be null");
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
        assertEquals(owner.fullName(), msg.getSystemMetadata().get("actorName"));
    }

    // =========================================================================
    // SM-02: addMembers 3 unique → 3 SYSTEM MEMBER_ADDED
    // =========================================================================

    @Test
    void SM02_addMembers_3unique_generates_3_MEMBER_ADDED_messages() throws Exception {
        RegUser owner = register("sm02owner@test.com", "sm02owner");
        RegUser b = register("sm02b@test.com", "sm02b");
        RegUser c = register("sm02c@test.com", "sm02c");
        UUID convId = createGroup(owner.token(), "SM02 Group", List.of(b.userId(), c.userId()));

        RegUser d = register("sm02d@test.com", "sm02d");
        RegUser e = register("sm02e@test.com", "sm02e");
        RegUser f = register("sm02f@test.com", "sm02f");

        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s","%s","%s"]}
                                """.formatted(d.userId(), e.userId(), f.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(3));

        long memberAddedCount = getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_ADDED".equals(m.getSystemEventType()))
                .count();
        assertEquals(3, memberAddedCount, "Expected 3 MEMBER_ADDED system messages");

        // Each must have targetId + targetName in metadata
        getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_ADDED".equals(m.getSystemEventType()))
                .forEach(m -> {
                    assertNotNull(m.getSystemMetadata().get("targetId"),
                            "MEMBER_ADDED must have targetId");
                    assertNotNull(m.getSystemMetadata().get("targetName"),
                            "MEMBER_ADDED must have targetName");
                    assertNotNull(m.getSystemMetadata().get("actorId"),
                            "MEMBER_ADDED must have actorId");
                });
    }

    // =========================================================================
    // SM-03: addMembers with 2 ALREADY_MEMBER skipped → only 1 SYSTEM
    // =========================================================================

    @Test
    void SM03_addMembers_skipAlreadyMember_generatesOnlyForAdded() throws Exception {
        RegUser owner = register("sm03owner@test.com", "sm03owner");
        RegUser b = register("sm03b@test.com", "sm03b");
        RegUser c = register("sm03c@test.com", "sm03c");
        UUID convId = createGroup(owner.token(), "SM03 Group", List.of(b.userId(), c.userId()));

        // b and c are already members, d is new
        RegUser d = register("sm03d@test.com", "sm03d");

        // No MEMBER_ADDED yet
        assertEquals(0, getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_ADDED".equals(m.getSystemEventType())).count());

        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s","%s","%s"]}
                                """.formatted(b.userId(), c.userId(), d.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(2));

        // Only 1 MEMBER_ADDED system message (for d, not for b/c)
        List<Message> addedMsgs = getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_ADDED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, addedMsgs.size(), "SYSTEM message only for added user, not for skipped");
        assertEquals(d.userId().toString(), addedMsgs.get(0).getSystemMetadata().get("targetId"));
    }

    // =========================================================================
    // SM-04: removeMember → 1 SYSTEM MEMBER_REMOVED
    // =========================================================================

    @Test
    void SM04_removeMember_generates_MEMBER_REMOVED_system_message() throws Exception {
        RegUser owner = register("sm04owner@test.com", "sm04owner");
        RegUser b = register("sm04b@test.com", "sm04b");
        RegUser c = register("sm04c@test.com", "sm04c");
        UUID convId = createGroup(owner.token(), "SM04 Group", List.of(b.userId(), c.userId()));

        mockMvc.perform(delete("/api/conversations/" + convId + "/members/" + b.userId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        List<Message> removedMsgs = getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_REMOVED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, removedMsgs.size(), "Expected 1 MEMBER_REMOVED system message");

        Message msg = removedMsgs.get(0);
        assertEquals(b.userId().toString(), msg.getSystemMetadata().get("targetId"));
        assertEquals(b.fullName(), msg.getSystemMetadata().get("targetName"));
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
    }

    // =========================================================================
    // SM-05: leaveGroup MEMBER → 1 SYSTEM MEMBER_LEFT
    // =========================================================================

    @Test
    void SM05_leaveGroup_member_generates_MEMBER_LEFT_system_message() throws Exception {
        RegUser owner = register("sm05owner@test.com", "sm05owner");
        RegUser b = register("sm05b@test.com", "sm05b");
        RegUser c = register("sm05c@test.com", "sm05c");
        UUID convId = createGroup(owner.token(), "SM05 Group", List.of(b.userId(), c.userId()));

        // b (MEMBER) leaves
        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isNoContent());

        List<Message> leftMsgs = getSystemMessages(convId).stream()
                .filter(m -> "MEMBER_LEFT".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, leftMsgs.size(), "Expected 1 MEMBER_LEFT system message");

        Message msg = leftMsgs.get(0);
        assertEquals(b.userId().toString(), msg.getSystemMetadata().get("actorId"),
                "actorId for MEMBER_LEFT must be the user who left");
    }

    // =========================================================================
    // SM-06: leaveGroup OWNER with ADMIN → OWNER_TRANSFERRED (index 0) BEFORE MEMBER_LEFT (index 1)
    // =========================================================================

    @Test
    void SM06_leaveGroup_owner_generates_OWNER_TRANSFERRED_before_MEMBER_LEFT() throws Exception {
        RegUser owner = register("sm06owner@test.com", "sm06owner");
        RegUser b = register("sm06b@test.com", "sm06b");
        RegUser c = register("sm06c@test.com", "sm06c");
        UUID convId = createGroup(owner.token(), "SM06 Group", List.of(b.userId(), c.userId()));
        promoteToAdmin(convId, b.userId());

        // Owner leaves → auto-transfer to b (ADMIN)
        mockMvc.perform(post("/api/conversations/" + convId + "/leave")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        // Get relevant system messages sorted by createdAt ASC
        List<Message> relevantMsgs = getSystemMessages(convId).stream()
                .filter(m -> "OWNER_TRANSFERRED".equals(m.getSystemEventType())
                        || "MEMBER_LEFT".equals(m.getSystemEventType()))
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .toList();

        assertEquals(2, relevantMsgs.size(),
                "Expected 2 system messages: OWNER_TRANSFERRED + MEMBER_LEFT");
        assertEquals("OWNER_TRANSFERRED", relevantMsgs.get(0).getSystemEventType(),
                "OWNER_TRANSFERRED must come FIRST (ordering constraint)");
        assertEquals("MEMBER_LEFT", relevantMsgs.get(1).getSystemEventType(),
                "MEMBER_LEFT must come SECOND");

        // OWNER_TRANSFERRED metadata
        Message transferredMsg = relevantMsgs.get(0);
        assertEquals(Boolean.TRUE, transferredMsg.getSystemMetadata().get("autoTransferred"),
                "autoTransferred must be true for auto-transfer on leave");
        assertEquals(b.userId().toString(), transferredMsg.getSystemMetadata().get("targetId"),
                "targetId must be the new owner");

        // MEMBER_LEFT actor is the leaving owner
        Message leftMsg = relevantMsgs.get(1);
        assertEquals(owner.userId().toString(), leftMsg.getSystemMetadata().get("actorId"),
                "MEMBER_LEFT actorId must be the leaving owner");
    }

    // =========================================================================
    // SM-07: changeRole MEMBER→ADMIN → SYSTEM ROLE_PROMOTED
    // =========================================================================

    @Test
    void SM07_changeRole_memberToAdmin_generates_ROLE_PROMOTED_system_message() throws Exception {
        RegUser owner = register("sm07owner@test.com", "sm07owner");
        RegUser b = register("sm07b@test.com", "sm07b");
        RegUser c = register("sm07c@test.com", "sm07c");
        UUID convId = createGroup(owner.token(), "SM07 Group", List.of(b.userId(), c.userId()));

        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId() + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        List<Message> promotedMsgs = getSystemMessages(convId).stream()
                .filter(m -> "ROLE_PROMOTED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, promotedMsgs.size(), "Expected 1 ROLE_PROMOTED system message");

        Message msg = promotedMsgs.get(0);
        assertEquals(b.userId().toString(), msg.getSystemMetadata().get("targetId"));
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
    }

    // =========================================================================
    // SM-08: changeRole ADMIN→MEMBER → SYSTEM ROLE_DEMOTED
    // =========================================================================

    @Test
    void SM08_changeRole_adminToMember_generates_ROLE_DEMOTED_system_message() throws Exception {
        RegUser owner = register("sm08owner@test.com", "sm08owner");
        RegUser b = register("sm08b@test.com", "sm08b");
        RegUser c = register("sm08c@test.com", "sm08c");
        UUID convId = createGroup(owner.token(), "SM08 Group", List.of(b.userId(), c.userId()));
        promoteToAdmin(convId, b.userId());

        mockMvc.perform(patch("/api/conversations/" + convId + "/members/" + b.userId() + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());

        List<Message> demotedMsgs = getSystemMessages(convId).stream()
                .filter(m -> "ROLE_DEMOTED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, demotedMsgs.size(), "Expected 1 ROLE_DEMOTED system message");

        Message msg = demotedMsgs.get(0);
        assertEquals(b.userId().toString(), msg.getSystemMetadata().get("targetId"));
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
    }

    // =========================================================================
    // SM-09: transferOwner → SYSTEM OWNER_TRANSFERRED with autoTransferred=false
    // =========================================================================

    @Test
    void SM09_transferOwner_generates_OWNER_TRANSFERRED_with_autoTransferred_false() throws Exception {
        RegUser owner = register("sm09owner@test.com", "sm09owner");
        RegUser b = register("sm09b@test.com", "sm09b");
        RegUser c = register("sm09c@test.com", "sm09c");
        UUID convId = createGroup(owner.token(), "SM09 Group", List.of(b.userId(), c.userId()));

        mockMvc.perform(post("/api/conversations/" + convId + "/transfer-owner")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + b.userId() + "\"}"))
                .andExpect(status().isOk());

        List<Message> transferredMsgs = getSystemMessages(convId).stream()
                .filter(m -> "OWNER_TRANSFERRED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, transferredMsgs.size(), "Expected 1 OWNER_TRANSFERRED system message");

        Message msg = transferredMsgs.get(0);
        assertEquals(Boolean.FALSE, msg.getSystemMetadata().get("autoTransferred"),
                "autoTransferred must be false for explicit transfer");
        assertEquals(b.userId().toString(), msg.getSystemMetadata().get("targetId"));
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
    }

    // =========================================================================
    // SM-10: updateGroupInfo rename → SYSTEM GROUP_RENAMED with oldValue + newValue
    // =========================================================================

    @Test
    void SM10_updateGroupInfo_rename_generates_GROUP_RENAMED_with_old_and_new_values() throws Exception {
        RegUser owner = register("sm10owner@test.com", "sm10owner");
        RegUser b = register("sm10b@test.com", "sm10b");
        RegUser c = register("sm10c@test.com", "sm10c");
        UUID convId = createGroup(owner.token(), "OldName", List.of(b.userId(), c.userId()));

        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewName\"}"))
                .andExpect(status().isOk());

        List<Message> renamedMsgs = getSystemMessages(convId).stream()
                .filter(m -> "GROUP_RENAMED".equals(m.getSystemEventType()))
                .toList();
        assertEquals(1, renamedMsgs.size(), "Expected 1 GROUP_RENAMED system message");

        Message msg = renamedMsgs.get(0);
        assertEquals("OldName", msg.getSystemMetadata().get("oldValue"),
                "oldValue must be the original group name");
        assertEquals("NewName", msg.getSystemMetadata().get("newValue"),
                "newValue must be the updated group name");
        assertEquals(owner.userId().toString(), msg.getSystemMetadata().get("actorId"));
    }

    // =========================================================================
    // SM-11: updateGroupInfo avatar only → 0 SYSTEM messages of type GROUP_RENAMED
    // =========================================================================

    @Test
    void SM11_updateGroupInfo_avatarOnly_generates_NO_GROUP_RENAMED_system_message() throws Exception {
        RegUser owner = register("sm11owner@test.com", "sm11owner");
        RegUser b = register("sm11b@test.com", "sm11b");
        RegUser c = register("sm11c@test.com", "sm11c");
        UUID convId = createGroup(owner.token(), "SM11 Group", List.of(b.userId(), c.userId()));

        // Remove avatar — avatar-only PATCH (no name change)
        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarFileId\":null}"))
                .andExpect(status().isOk());

        long renamedCount = getSystemMessages(convId).stream()
                .filter(m -> "GROUP_RENAMED".equals(m.getSystemEventType()))
                .count();
        assertEquals(0, renamedCount,
                "Avatar-only update must NOT generate GROUP_RENAMED system message");
    }

    // =========================================================================
    // SM-12: MessageService.editViaStomp SYSTEM message → SYSTEM_MESSAGE_NOT_EDITABLE (403)
    // =========================================================================

    @Test
    void SM12_editViaStomp_system_message_throws_SYSTEM_MESSAGE_NOT_EDITABLE() throws Exception {
        RegUser owner = register("sm12owner@test.com", "sm12owner");
        RegUser b = register("sm12b@test.com", "sm12b");
        RegUser c = register("sm12c@test.com", "sm12c");
        UUID convId = createGroup(owner.token(), "SM12 Group", List.of(b.userId(), c.userId()));

        // Get the GROUP_CREATED system message
        List<Message> sysMsgs = getSystemMessages(convId);
        assertFalse(sysMsgs.isEmpty(), "Precondition: GROUP_CREATED system message must exist. Found: " + sysMsgs.size());

        Message sysMsg = sysMsgs.stream()
                .filter(m -> "GROUP_CREATED".equals(m.getSystemEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected GROUP_CREATED system message in " + sysMsgs));

        // Verify it's accessible via findById
        Message found = messageRepository.findById(sysMsg.getId()).orElse(null);
        assertNotNull(found, "System message must be findable by id: " + sysMsg.getId());
        assertEquals(MessageType.SYSTEM, found.getType(), "Found message must be SYSTEM type");

        String clientEditId = UUID.randomUUID().toString();
        EditMessagePayload editPayload = new EditMessagePayload(
                clientEditId, sysMsg.getId(), "new content"
        );

        // Test the guard: editViaStomp should throw for SYSTEM messages
        // Note: editViaStomp is @Transactional, so we call via the Spring-proxied bean
        // If AppException is not thrown, check if maybe a different exception is thrown or
        // if the method completes silently (should not happen with SYSTEM type guard)
        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, owner.userId(), editPayload),
                "Expected AppException for editing SYSTEM message. "
                        + "systemMsgId=" + sysMsg.getId() + ", type=" + sysMsg.getType()
                        + ", convId=" + convId + ", userId=" + owner.userId());

        assertEquals("SYSTEM_MESSAGE_NOT_EDITABLE", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // =========================================================================
    // SM-13: MessageService.deleteViaStomp SYSTEM message → SYSTEM_MESSAGE_NOT_DELETABLE (403)
    // =========================================================================

    @Test
    void SM13_deleteViaStomp_system_message_throws_SYSTEM_MESSAGE_NOT_DELETABLE() throws Exception {
        RegUser owner = register("sm13owner@test.com", "sm13owner");
        RegUser b = register("sm13b@test.com", "sm13b");
        RegUser c = register("sm13c@test.com", "sm13c");
        UUID convId = createGroup(owner.token(), "SM13 Group", List.of(b.userId(), c.userId()));

        // Get the GROUP_CREATED system message
        Message sysMsg = getSystemMessages(convId).stream()
                .filter(m -> "GROUP_CREATED".equals(m.getSystemEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected GROUP_CREATED system message"));

        String clientDeleteId = UUID.randomUUID().toString();
        DeleteMessagePayload deletePayload = new DeleteMessagePayload(
                clientDeleteId, sysMsg.getId()
        );

        AppException ex = assertThrows(AppException.class,
                () -> messageService.deleteViaStomp(convId, owner.userId(), deletePayload),
                "Expected AppException for deleting SYSTEM message");

        assertEquals("SYSTEM_MESSAGE_NOT_DELETABLE", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
