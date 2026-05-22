package com.enrollment.domain;

import jakarta.persistence.*;

// 회원 엔티티. 테이블명 'users' — 'user'는 PostgreSQL 예약어
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    protected User() {}

    public User(String name, UserRole role) {
        this.name = name;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public UserRole getRole() { return role; }
}
