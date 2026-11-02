package com.glofox.memberships.testharness.api;

import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_SETTINGS_PATH;
import static com.glofox.memberships.testharness.constant.Constants.ADMIN_IDENTITY_HEADER;

import com.glofox.memberships.testharness.service.TestHarnessSettingsService.SettingView;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TEST_HARNESS_SETTINGS_PATH)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-harness", name = "enabled", havingValue = "true")
public class TestHarnessSettingsController {
    private final TestHarnessSettingsService settings;
    @GetMapping public List<SettingView> all() { return settings.all(); }
    @GetMapping("/{key}") public SettingView get(@PathVariable String key) { return settings.get(key); }
    @PutMapping("/{key}") public SettingView put(@PathVariable String key, @RequestBody UpdateSetting request,
            @RequestHeader(value = ADMIN_IDENTITY_HEADER, required = false) String identity) {
        return settings.update(key, request.value(), request.enabled(), identity);
    }
    public record UpdateSetting(String value, boolean enabled) {}
}
