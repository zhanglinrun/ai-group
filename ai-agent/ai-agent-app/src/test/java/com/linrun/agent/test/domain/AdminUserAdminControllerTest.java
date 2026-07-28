package com.linrun.agent.test.domain;

import com.linrun.agent.types.common.JsonUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.api.dto.AdminUserLoginRequestDTO;
import com.linrun.agent.api.dto.AdminUserRequestDTO;
import com.linrun.agent.api.dto.AdminUserResponseDTO;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.infrastructure.dao.IAdminUserDao;
import com.linrun.agent.infrastructure.dao.po.AdminUser;
import com.linrun.agent.trigger.http.admin.AdminUserAdminController;
import com.linrun.agent.types.enums.ResponseCode;

import java.time.LocalDateTime;

/**
 * AdminUserAdminController BCrypt 登录与密码存储离线单测（mock DAO，不依赖 MySQL）。
 */
public class AdminUserAdminControllerTest {

    private static final String RAW_PASSWORD = "admin-pass-123";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminUserAdminController controller;
    private IAdminUserDao adminUserDao;

    @Before
    public void setUp() {
        controller = new AdminUserAdminController();
        adminUserDao = Mockito.mock(IAdminUserDao.class);
        ReflectionTestUtils.setField(controller, "adminUserDao", adminUserDao);
    }

    private AdminUser adminUser(String storedPassword, int status) {
        return AdminUser.builder()
                .id(1L)
                .userId("admin_001")
                .username("admin")
                .password(storedPassword)
                .status(status)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private AdminUserLoginRequestDTO loginRequest(String password) {
        return AdminUserLoginRequestDTO.builder()
                .username("admin")
                .password(password)
                .build();
    }

    @Test
    public void shouldLoginSuccessWithBCryptStoredPassword() {
        Mockito.when(adminUserDao.queryByUsername("admin"))
                .thenReturn(adminUser(encoder.encode(RAW_PASSWORD), 1));

        Response<AdminUserResponseDTO> response = controller.loginAdminUser(loginRequest(RAW_PASSWORD));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("admin", response.getData().getUsername());
        // 已是 BCrypt 哈希，不应触发存量明文升级
        Mockito.verify(adminUserDao, Mockito.never()).updateById(Mockito.any());
    }

    @Test
    public void shouldRejectLoginWhenPasswordWrong() {
        Mockito.when(adminUserDao.queryByUsername("admin"))
                .thenReturn(adminUser(encoder.encode(RAW_PASSWORD), 1));

        Response<AdminUserResponseDTO> response = controller.loginAdminUser(loginRequest("wrong-password"));

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        Assert.assertNull(response.getData());
    }

    @Test
    public void shouldRejectLoginWhenUserDisabled() {
        Mockito.when(adminUserDao.queryByUsername("admin"))
                .thenReturn(adminUser(encoder.encode(RAW_PASSWORD), 0));

        Response<AdminUserResponseDTO> response = controller.loginAdminUser(loginRequest(RAW_PASSWORD));

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        Assert.assertEquals("用户已被禁用", response.getInfo());
        Assert.assertNull(response.getData());
    }

    @Test
    public void shouldUpgradeLegacyPlaintextPasswordOnLogin() {
        Mockito.when(adminUserDao.queryByUsername("admin"))
                .thenReturn(adminUser(RAW_PASSWORD, 1));

        Response<AdminUserResponseDTO> response = controller.loginAdminUser(loginRequest(RAW_PASSWORD));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        Mockito.verify(adminUserDao).updateById(captor.capture());
        String upgraded = captor.getValue().getPassword();
        Assert.assertTrue("存量明文应升级为 BCrypt 哈希", upgraded.startsWith("$2"));
        Assert.assertTrue(encoder.matches(RAW_PASSWORD, upgraded));
    }

    @Test
    public void shouldNotExposePasswordInLoginAndValidateResponse() {
        String storedHash = encoder.encode(RAW_PASSWORD);
        Mockito.when(adminUserDao.queryByUsername("admin"))
                .thenReturn(adminUser(storedHash, 1));

        Response<AdminUserResponseDTO> loginResponse = controller.loginAdminUser(loginRequest(RAW_PASSWORD));
        String loginJson = JsonUtils.toJson(loginResponse);
        Assert.assertFalse("登录响应不得包含明文密码", loginJson.contains(RAW_PASSWORD));
        Assert.assertFalse("登录响应不得包含密码哈希", loginJson.contains(storedHash));

        Response<Boolean> validateResponse = controller.validateAdminUserLogin(loginRequest(RAW_PASSWORD));
        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), validateResponse.getCode());
        Assert.assertTrue(validateResponse.getData());
    }

    @Test
    public void shouldStoreBCryptHashOnCreate() {
        Mockito.when(adminUserDao.insert(Mockito.any())).thenReturn(1);

        AdminUserRequestDTO request = AdminUserRequestDTO.builder()
                .userId("admin_002")
                .username("admin2")
                .password(RAW_PASSWORD)
                .status(1)
                .build();
        Response<Boolean> response = controller.createAdminUser(request);

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        Mockito.verify(adminUserDao).insert(captor.capture());
        String stored = captor.getValue().getPassword();
        Assert.assertTrue("落库密码必须是 BCrypt 哈希", stored.startsWith("$2"));
        Assert.assertNotEquals(RAW_PASSWORD, stored);
        Assert.assertTrue(encoder.matches(RAW_PASSWORD, stored));
    }

    @Test
    public void shouldKeepExistingPasswordWhenUpdateWithoutPassword() {
        Mockito.when(adminUserDao.updateById(Mockito.any())).thenReturn(1);

        AdminUserRequestDTO request = AdminUserRequestDTO.builder()
                .id(1L)
                .userId("admin_001")
                .username("admin")
                .status(1)
                .build();
        Response<Boolean> response = controller.updateAdminUserById(request);

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        Mockito.verify(adminUserDao).updateById(captor.capture());
        // 空密码置 null，交由 mapper <if> 跳过 password 列更新，避免清空已有哈希
        Assert.assertNull(captor.getValue().getPassword());
    }

}
