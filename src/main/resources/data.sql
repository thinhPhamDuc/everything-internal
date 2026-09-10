-- Task 3: seed Role/Permission mặc định. Idempotent (ON CONFLICT DO NOTHING)
-- vì data.sql chạy lại mỗi lần app start (ddl-auto=update không xoá bảng cũ
-- giữa các lần chạy).

INSERT INTO roles (name, description) VALUES
    ('ADMIN', 'Toan quyen quan tri he thong'),
    ('STAFF', 'Nhan vien van hanh - duoc cap quyen quan ly user'),
    ('CUSTOMER', 'Khach hang - khong co quyen quan tri')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (code, description) VALUES
    ('USER_READ', 'Xem danh sach / chi tiet user'),
    ('USER_MANAGE', 'Sua, doi status, xoa, gan role cho user'),
    ('ROLE_MANAGE', 'CRUD role va gan permission cho role')
ON CONFLICT (code) DO NOTHING;

-- ADMIN: full quyen
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.code IN ('USER_READ', 'USER_MANAGE', 'ROLE_MANAGE')
ON CONFLICT DO NOTHING;

-- STAFF: quan ly user nhung khong dung toi Role/Permission
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'STAFF' AND p.code IN ('USER_READ', 'USER_MANAGE')
ON CONFLICT DO NOTHING;

-- CUSTOMER: khong co permission quan tri nao.
