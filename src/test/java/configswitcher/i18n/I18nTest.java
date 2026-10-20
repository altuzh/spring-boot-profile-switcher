package configswitcher.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class I18nTest {

    @Test
    public void testLanguageFromCode() {
        assertEquals(PluginLanguage.EN, PluginLanguage.fromCode("en"));
        assertEquals(PluginLanguage.EN, PluginLanguage.fromCode("EN"));
        assertEquals(PluginLanguage.RU, PluginLanguage.fromCode("ru"));
        assertEquals(PluginLanguage.RU, PluginLanguage.fromCode("RU"));
        assertEquals(PluginLanguage.EN, PluginLanguage.fromCode("unknown"));
        assertEquals(PluginLanguage.EN, PluginLanguage.fromCode(null));
    }

    @Test
    public void testTranslationsExistForBothLanguages() {
        String[] keys = {
                "settings.language.title",
                "settings.language.label",
                "settings.patches.title",
                "settings.patches.col.active",
                "settings.patches.col.path",
                "settings.patches.col.description",
                "settings.patches.btn.paste",
                "settings.patches.btn.add",
                "settings.patches.btn.remove",
                "settings.patches.btn.revert",
                "settings.patches.revert_before_apply",
                "settings.patches.auto_revert",
                "settings.patches.storage_folder",
                "settings.build.title",
                "settings.build.enable",
                "settings.build.command_label",
                "settings.build.skip_if_running",
                "settings.run.title",
                "settings.run.template_label",
                "settings.run.recalc_btn",
                "settings.run.log_levels_label",
                "settings.run.colorize_error",
                "settings.run.call_stack",
                "settings.mesh.title",
                "settings.mesh.enable",
                "settings.mesh.endpoint_label",
                "settings.mesh.max_history_label",
                "settings.logs.title",
                "settings.logs.folder_prefix",
                "settings.logs.desc",
                "settings.logs.open_btn",
                "settings.logs.reload_btn",
                "dialog.paste_patch.title",
                "dialog.paste_patch.name_label",
                "dialog.paste_patch.desc_label",
                "dialog.paste_patch.preview_label",
                "dialog.paste_patch.empty_name_title",
                "dialog.paste_patch.empty_name_msg",
                "dialog.paste_patch.save_error_title",
                "dialog.patches.reverted.title",
                "dialog.patches.empty_clipboard.title",
                "dialog.patches.empty_clipboard.msg",
                "dialog.patches.select_file.title",
                "action.profile_switcher.text",
                "action.profile_switcher.desc",
                "action.start",
                "action.start.desc",
                "action.stop",
                "action.stop.desc",
                "action.rebuild_restart",
                "action.rebuild_restart.desc",
                "action.run_restart",
                "action.run_restart.desc",
                "action.header.spring_configs",
                "action.header.actions",
                "action.no_configs",
                "action.no_configs.desc",
                "action.revert_patches",
                "action.revert_patches.desc",
                "action.reload_plugin",
                "action.reload_plugin.desc",
                "action.settings",
                "action.settings.desc",
                "action.diff.title",
                "action.diff.desc"
        };

        for (String key : keys) {
            String en = I18n.get(PluginLanguage.EN, key);
            String ru = I18n.get(PluginLanguage.RU, key);

            assertNotNull(en, "EN translation missing for: " + key);
            assertFalse(en.isBlank(), "EN translation is blank for: " + key);
            assertNotEquals(key, en, "EN key fell through untranslated: " + key);

            assertNotNull(ru, "RU translation missing for: " + key);
            assertFalse(ru.isBlank(), "RU translation is blank for: " + key);
            assertNotEquals(key, ru, "RU key fell through untranslated: " + key);

            assertNotEquals(en, ru, "RU translation equals EN translation for: " + key);
        }
    }

    @Test
    public void testStringFormatting() {
        assertEquals("Profile: dev", I18n.get(PluginLanguage.EN, "action.profile_switcher.text", "dev"));
        assertEquals("Профиль: dev", I18n.get(PluginLanguage.RU, "action.profile_switcher.text", "dev"));

        assertEquals("Start [stage]", I18n.get(PluginLanguage.EN, "action.start", "stage"));
        assertEquals("Запустить [stage]", I18n.get(PluginLanguage.RU, "action.start", "stage"));

        assertEquals("Reverted 3 git patch(es).", I18n.get(PluginLanguage.EN, "dialog.patches.reverted.msg", 3));
        assertEquals("Откатано git-патчей: 3.", I18n.get(PluginLanguage.RU, "dialog.patches.reverted.msg", 3));
    }

    @Test
    public void testMeshTranslationsExist() {
        String[] meshKeys = {
                "mesh.tab.live_requests",
                "mesh.tab.error_analysis",
                "mesh.tab.log_scanner",
                "mesh.btn.scan_log",
                "mesh.btn.paste_log",
                "mesh.btn.paste_logs",
                "mesh.btn.clear",
                "mesh.btn.pause",
                "mesh.btn.resume",
                "mesh.btn.export_json",
                "mesh.btn.log_folder",
                "mesh.btn.refresh",
                "mesh.btn.copy_all",
                "mesh.btn.copy_query",
                "mesh.btn.copy_curl",
                "mesh.btn.copy_response",
                "mesh.btn.copy_variables",
                "mesh.btn.copy_logs",
                "mesh.btn.copy_stack",
                "mesh.btn.copy_cause",
                "mesh.btn.copy_error_report",
                "mesh.btn.export_report",
                "mesh.btn.compare_success",
                "mesh.btn.search_web",
                "mesh.btn.jump_app",
                "mesh.btn.jump_line",
                "mesh.btn.analyze_in_studio",
                "mesh.btn.previous_log",
                "mesh.btn.current_log",
                "mesh.chk.auto_scroll",
                "mesh.label.level",
                "mesh.label.type",
                "mesh.label.status",
                "mesh.label.category",
                "mesh.label.error_types",
                "mesh.filter.all_levels",
                "mesh.filter.all_types",
                "mesh.filter.all_requests",
                "mesh.filter.all_errors",
                "mesh.filter.ok",
                "mesh.filter.success",
                "mesh.filter.pending",
                "mesh.empty.title",
                "mesh.empty.subtitle",
                "mesh.empty.scan_btn",
                "mesh.empty.paste_btn",
                "mesh.empty.folder_btn",
                "mesh.detail.tab.error",
                "mesh.detail.tab.error_active",
                "mesh.detail.tab.diag_ok",
                "mesh.detail.tab.query",
                "mesh.detail.tab.response",
                "mesh.detail.tab.variables",
                "mesh.detail.tab.trace",
                "mesh.detail.tab.raw_logs",
                "mesh.detail.root_cause_title",
                "mesh.detail.stack_trace_title",
                "mesh.detail.ok_text",
                "mesh.detail.ok_sub",
                "mesh.detail.no_stack",
                "mesh.error.search_hint",
                "mesh.error.chip_all",
                "mesh.error.chip_gql",
                "mesh.error.chip_validation",
                "mesh.error.chip_http5xx",
                "mesh.error.chip_http4xx",
                "mesh.error.chip_timeout",
                "mesh.error.chip_app",
                "mesh.error.empty_title",
                "mesh.error.empty_sub",
                "mesh.error.empty_scan_btn",
                "mesh.error.empty_paste_btn",
                "mesh.error.empty_folder_btn",
                "mesh.table.col.num",
                "mesh.table.col.time",
                "mesh.table.col.level",
                "mesh.table.col.status",
                "mesh.table.col.http",
                "mesh.table.col.type",
                "mesh.table.col.operation",
                "mesh.table.col.latency",
                "mesh.table.col.trace_id",
                "mesh.table.col.size",
                "mesh.dialog.paste.title",
                "mesh.dialog.paste.button",
                "mesh.dialog.paste.instruction"
        };

        for (String key : meshKeys) {
            String en = I18n.get(PluginLanguage.EN, key);
            String ru = I18n.get(PluginLanguage.RU, key);

            assertNotNull(en, "EN translation missing for: " + key);
            assertFalse(en.isBlank(), "EN translation is blank for: " + key);
            assertNotEquals(key, en, "EN key fell through untranslated: " + key);

            assertNotNull(ru, "RU translation missing for: " + key);
            assertFalse(ru.isBlank(), "RU translation is blank for: " + key);
            assertNotEquals(key, ru, "RU key fell through untranslated: " + key);
        }
    }

    @Test
    public void testNewHelpAndTooltipKeysExist() {
        String[] newKeys = {
                "help.mesh.chrome_plugin",
                "help.mesh.address",
                "help.mesh.inspector_filters",
                "help.mesh.inspector_actions",
                "help.mesh.error_categories",
                "help.mesh.log_scanner",
                "mesh.btn.open_in_mesh",
                "mesh.btn.open_in_mesh.tooltip",
                "mesh.btn.add_address.tooltip",
                "mesh.btn.remove_address.tooltip",
                "mesh.btn.scan_log.tooltip",
                "mesh.btn.paste_log.tooltip",
                "mesh.btn.clear.tooltip",
                "mesh.btn.pause.tooltip",
                "mesh.btn.resume.tooltip",
                "mesh.btn.export_json.tooltip",
                "mesh.btn.log_folder.tooltip",
                "mesh.btn.errors_only.tooltip",
                "mesh.btn.mesh_only.tooltip",
                "mesh.btn.copy_query.tooltip",
                "mesh.btn.copy_curl.tooltip",
                "mesh.btn.copy_response.tooltip",
                "mesh.btn.copy_variables.tooltip",
                "mesh.btn.copy_logs.tooltip",
                "mesh.btn.copy_error_report.tooltip",
                "mesh.btn.compare_success.tooltip",
                "mesh.btn.search_web.tooltip",
                "mesh.btn.copy_cause.tooltip",
                "mesh.btn.jump_app.tooltip",
                "mesh.btn.jump_line.tooltip",
                "mesh.btn.copy_stack.tooltip",
                "mesh.btn.analyze_in_studio.tooltip",
                "mesh.btn.refresh.tooltip",
                "mesh.btn.previous_log.tooltip",
                "mesh.btn.paste_logs.tooltip",
                "mesh.btn.copy_all.tooltip",
                "mesh.btn.export_report.tooltip",
                "mesh.search.placeholder"
        };

        for (String key : newKeys) {
            String en = I18n.get(PluginLanguage.EN, key);
            String ru = I18n.get(PluginLanguage.RU, key);

            assertNotNull(en, "EN translation missing for: " + key);
            assertFalse(en.isBlank(), "EN translation is blank for: " + key);
            assertNotEquals(key, en, "EN key fell through untranslated: " + key);

            assertNotNull(ru, "RU translation missing for: " + key);
            assertFalse(ru.isBlank(), "RU translation is blank for: " + key);
            assertNotEquals(key, ru, "RU key fell through untranslated: " + key);

            assertNotEquals(en, ru, "RU translation equals EN translation for: " + key);
        }
    }

    @Test
    public void testMissingKeyFallback() {
        assertEquals("some.nonexistent.key", I18n.get(PluginLanguage.EN, "some.nonexistent.key"));
        assertEquals("some.nonexistent.key", I18n.get(PluginLanguage.RU, "some.nonexistent.key"));
    }
}
