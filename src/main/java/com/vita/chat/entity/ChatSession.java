package com.vita.chat.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "guest_id")
    private UUID guestId;
    
    private String title;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    
    @Builder
    public ChatSession(Long userId, UUID guestId, String title){
    	this.userId = userId;
    	this.guestId = guestId;
    	this.title = title;
    }
    
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        this.updatedAt = this.createdAt;
    }
    
    public void update() {
    	this.updatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
    
 /**
  * 세션 소유권 판별 헬퍼
  * @param userId
  * @param guestId
  * @return
  */
    public boolean isOwnedBy(Long userId, UUID guestId) {
        if (this.userId != null) return this.userId.equals(userId);
        return this.guestId != null && this.guestId.equals(guestId);
    }
}
