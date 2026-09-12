-- Task 3: seed Role/Permission mặc định. Idempotent (INSERT IGNORE)
-- vì data.sql chạy lại mỗi lần app start (ddl-auto=update không xoá bảng cũ
-- giữa các lần chạy).

INSERT IGNORE INTO roles (name, description) VALUES
    ('ADMIN', 'Toan quyen quan tri he thong'),
    ('STAFF', 'Nhan vien van hanh - duoc cap quyen quan ly user'),
    ('CUSTOMER', 'Khach hang - khong co quyen quan tri')
;

INSERT IGNORE INTO permissions (code, description) VALUES
    ('USER_READ', 'Xem danh sach / chi tiet user'),
    ('USER_MANAGE', 'Sua, doi status, xoa, gan role cho user'),
    ('ROLE_MANAGE', 'CRUD role va gan permission cho role'),
    ('INVENTORY_READ', 'Xem danh sach / chi tiet ve may bay trong kho'),
    ('INVENTORY_MANAGE', 'Tao, sua, doi status, dong ve may bay trong kho')
;

-- ADMIN: full quyen
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.code IN ('USER_READ', 'USER_MANAGE', 'ROLE_MANAGE', 'INVENTORY_READ', 'INVENTORY_MANAGE')
;

-- STAFF: quan ly user + quan ly inventory (nhan vien van hanh), khong dung toi Role/Permission
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'STAFF' AND p.code IN ('USER_READ', 'USER_MANAGE', 'INVENTORY_READ', 'INVENTORY_MANAGE')
;

-- CUSTOMER: khong co permission quan tri nao.
