package com.app.internal.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Kiểm chứng đúng quyết định thiết kế ở TASK4_CRUD_INVENTORY.md Bước 9:
// InventoryController dùng hasAuthority('INVENTORY_MANAGE') (permission-based,
// giống UserController) - STAFF được seed permission này trong data.sql nên
// phải gọi được, ADMIN thuần role không có permission thì KHÔNG được (khác
// với RoleController vẫn dùng hasRole('ADMIN') tuyệt đối).
@SpringBootTest
class InventoryControllerHttpTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_MANAGE")
    void listInventory_coPermissionInventoryManage_tra200() throws Exception {
        mockMvc.perform(get("/admin/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listInventory_chiCoRoleAdminKhongCoPermission_tra403() throws Exception {
        mockMvc.perform(get("/admin/inventory"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listInventory_khongCoToken_tra403() throws Exception {
        mockMvc.perform(get("/admin/inventory"))
                .andExpect(status().isForbidden());
    }
}
