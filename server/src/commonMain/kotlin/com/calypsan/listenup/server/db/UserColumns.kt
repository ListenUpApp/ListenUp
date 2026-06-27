package com.calypsan.listenup.server.db

/**
 * Server-side enum for `users.role`. Maps 1:1 with commonMain `UserRole`; stored by `.name` in the
 * `users.role` column. A plain enum (no persistence-engine dependency) — read/written by the SQLDelight
 * users queries and the auth/admin services.
 */
enum class UserRoleColumn { ROOT, ADMIN, MEMBER }

/**
 * Server-side enum for `users.status`. Maps 1:1 with commonMain `UserStatus`; stored by `.name` in the
 * `users.status` column. A plain enum (no persistence-engine dependency).
 */
enum class UserStatusColumn { ACTIVE, PENDING_APPROVAL, DENIED }
