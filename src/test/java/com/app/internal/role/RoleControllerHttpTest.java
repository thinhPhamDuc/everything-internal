package com.app.internal.role;

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

// Kiểm chứng đúng quyết định thiết kế ở TASK3_CRUD_ROLES.md Bước 11:
// RoleController CỐ Ý giữ hasRole('ADMIN') tuyệt đối, khác với UserController
// đã đổi sang hasAuthority theo permission - 1 user có permission USER_MANAGE
// (VD role STAFF) nhưng KHÔNG phải ADMIN vẫn phải bị chặn ở đây.
@SpringBootTest
class RoleControllerHttpTest {

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
    @WithMockUser(roles = "ADMIN")
    void listRoles_bangAdmin_tra200() throws Exception {
        mockMvc.perform(get("/admin/roles"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void listRoles_coPermissionUserManageNhungKhongPhaiAdmin_tra403() throws Exception {
        mockMvc.perform(get("/admin/roles"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listRoles_khongCoToken_tra403() throws Exception {
        mockMvc.perform(get("/admin/roles"))
                .andExpect(status().isForbidden());
    }
}
