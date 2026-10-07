package configswitcher.i18n;

import com.intellij.openapi.project.Project;
import configswitcher.state.PluginSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class I18n {

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> RU = new HashMap<>();

    static {
        // ==========================================
        // Settings - Language
        // ==========================================
        put("settings.language.title", "Language / Язык", "Язык интерфейса");
        put("settings.language.label", "Interface Language:", "Язык интерфейса:");

        // ==========================================
        // Settings - Pre-Run Stage 1: Git Patches
        // ==========================================
        put("settings.patches.title",
                "Pre-Run Stage 1: Git Patches (Auto-applied before build, reverted after build)",
                "Этап 1: Git-патчи (Автоприменение перед сборкой, откат после сборки)");
        put("settings.patches.col.active", "Active", "Активен");
        put("settings.patches.col.path", "Patch File Path", "Путь к файлу патча");
        put("settings.patches.col.description", "Description", "Описание");
        put("settings.patches.btn.paste", "Paste from Clipboard...", "Вставить из буфера...");
        put("settings.patches.btn.paste.tooltip",
                "Paste git diff from clipboard, save as file in project (.idea/patches/), and add to list",
                "Вставить git diff из буфера обмена, сохранить в проекте (.idea/patches/) и добавить в список");
        put("settings.patches.btn.add", "Add File...", "Добавить файл...");
        put("settings.patches.btn.remove", "Remove Selected", "Удалить выбранное");
        put("settings.patches.btn.revert", "Revert Applied Patches Now", "Откатить примененные патчи сейчас");
        put("settings.patches.btn.revert.tooltip",
                "Manually reverse any git patches that are currently applied to the working copy",
                "Вручную откатить примененные git-патчи из рабочей копии");
        put("settings.patches.revert_before_apply",
                "Automatically revert changes in patch files before applying (Pre-Run Stage 1)",
                "Автоматически откатывать изменения в файлах патча перед применением (Этап 1)");
        put("settings.patches.revert_before_apply.tooltip",
                "Discards any local uncommitted/dirty changes in files targeted by git patches to ensure patches apply cleanly",
                "Сбрасывает локальные незакоммиченные изменения в целевых файлах патча для чистого применения");
        put("settings.patches.auto_revert",
                "Automatically revert applied patches after build stage finishes (and on exit)",
                "Автоматически откатывать примененные патчи после завершения сборки (и при выходе)");
        put("settings.patches.auto_revert.tooltip",
                "Reverts git patches immediately after the pre-run build finishes, keeping your working tree clean while the application runs",
                "Откатывает git-патчи сразу после сборки, сохраняя рабочую копию чистой во время работы приложения");
        put("settings.patches.storage_folder", "Patch storage folder in project:", "Папка хранения патчей в проекте:");

        // ==========================================
        // Settings - Pre-Run Stage 2: Build Command
        // ==========================================
        put("settings.build.title",
                "Pre-Run Stage 2: Build Command (e.g. Maven/Gradle build)",
                "Этап 2: Команда сборки (например, сборка Maven/Gradle)");
        put("settings.build.enable",
                "Execute Pre-Run build command before launching application",
                "Выполнять команду предварительной сборки перед запуском приложения");
        put("settings.build.command_label", "Pre-Run Command:", "Команда предсборки:");
        put("settings.build.skip_if_running",
                "Skip Pre-Run build if application is already running",
                "Пропускать предварительную сборку, если приложение уже запущено");

        // ==========================================
        // Settings - Run Stage: Application Command
        // ==========================================
        put("settings.run.title",
                "Run Stage: Application Command (Command Pipeline Mode)",
                "Этап запуска: Команда приложения (Режим Command Pipeline)");
        put("settings.run.template_label",
                "Command Template (placeholders: {profile}, {filePath}):",
                "Шаблон команды (подстановки: {profile}, {filePath}):");
        put("settings.run.recalc_btn", "Recalculate Command Template", "Пересчитать шаблон команды");
        put("settings.run.recalc_tooltip",
                "Auto-detect target JAR and N2O config path from current project",
                "Автоопределение целевого JAR и пути к конфигурации N2O из текущего проекта");
        put("settings.run.log_levels_label", "Terminal Output Log Levels:", "Уровни логов в терминале:");
        put("settings.run.log_debug_tooltip",
                "Output DEBUG and TRACE logs to terminal console",
                "Выводить логи DEBUG и TRACE в консоль терминала");
        put("settings.run.log_info_tooltip",
                "Output INFO logs to terminal console",
                "Выводить логи INFO в консоль терминала");
        put("settings.run.log_warn_tooltip",
                "Output WARN logs to terminal console",
                "Выводить логи WARN в консоль терминала");
        put("settings.run.log_error_tooltip",
                "Output ERROR logs to terminal console",
                "Выводить логи ERROR в консоль терминала");
        put("settings.run.colorize_error", "Paint errors in red", "Выделять ошибки красным");
        put("settings.run.colorize_error_tooltip",
                "Highlight error log messages in red ANSI color in the terminal",
                "Подсвечивать сообщения об ошибках красным цветом ANSI в терминале");
        put("settings.run.call_stack", "Call stack", "Стек вызовов");
        put("settings.run.call_stack_tooltip",
                "Output error call stack (stack trace lines) to terminal console",
                "Выводить стек вызовов ошибок (строки трассировки) в консоль терминала");

        // ==========================================
        // Settings - GraphQL Mesh Observability
        // ==========================================
        put("settings.mesh.title", "GraphQL Mesh Observability", "Мониторинг GraphQL Mesh");
        put("settings.mesh.enable",
                "Enable GraphQL Mesh request & response analyzer",
                "Включить анализатор запросов и ответов GraphQL Mesh");
        put("settings.mesh.endpoint_label", "Default GraphQL Endpoint:", "URL GraphQL конечной точки по умолчанию:");
        put("settings.mesh.max_history_label", "Max Captured History Entries:", "Макс. записей истории:");

        // ==========================================
        // Settings - Diagnostics & Logs
        // ==========================================
        put("settings.logs.title", "Plugin Logs & Diagnostics", "Логи и диагностика плагина");
        put("settings.logs.folder_prefix", "Log folder:", "Папка логов:");
        put("settings.logs.desc",
                "Stores session.log (application run output), mesh-session.json, and diagnostic logs for pipeline execution.",
                "Содержит session.log (вывод запущенного приложения), mesh-session.json и логи выполнения пайплайна.");
        put("settings.logs.open_btn", "Open Log Folder", "Открыть папку логов");
        put("settings.logs.reload_btn", "Reload Plugin from Disk", "Перезагрузить плагин с диска");

        // ==========================================
        // Dialogs & Notifications
        // ==========================================
        put("dialog.paste_patch.title", "Create Git Patch from Clipboard", "Создать Git-патч из буфера обмена");
        put("dialog.paste_patch.name_label", "Patch File Name:", "Имя файла патча:");
        put("dialog.paste_patch.desc_label", "Description:", "Описание:");
        put("dialog.paste_patch.preview_label", "Clipboard Content Preview:", "Предпросмотр содержимого буфера:");
        put("dialog.paste_patch.empty_name_title", "Invalid Patch Name", "Неверное имя патча");
        put("dialog.paste_patch.empty_name_msg", "Please enter a valid patch file name.", "Пожалуйста, введите корректное имя файла патча.");
        put("dialog.paste_patch.save_error_title", "Error Saving Patch", "Ошибка сохранения патча");
        put("dialog.paste_patch.save_error_msg", "Failed to create patch: {0}", "Не удалось создать патч: {0}");
        put("dialog.patches.reverted.title", "Git Patches Reverted", "Git-патчи откатаны");
        put("dialog.patches.reverted.msg", "Reverted {0} git patch(es).", "Откатано git-патчей: {0}.");
        put("dialog.patches.empty_clipboard.title", "Paste Git Patch", "Вставка Git-патча");
        put("dialog.patches.empty_clipboard.msg", "Clipboard is empty or does not contain text.", "Буфер обмена пуст или не содержит текста.");
        put("dialog.patches.select_file.title", "Select Git Patch File", "Выберите файл Git-патча");

        // ==========================================
        // Actions & Toolbars
        // ==========================================
        put("action.profile_switcher.text", "Profile: {0}", "Профиль: {0}");
        put("action.profile_switcher.desc", "Active Profile: {0} ({1})", "Активный профиль: {0} ({1})");
        put("action.start", "Start [{0}]", "Запустить [{0}]");
        put("action.start.desc", "Start application with selected profile '{0}'", "Запустить приложение с выбранным профилем '{0}'");
        put("action.stop", "Stop [{0}]", "Остановить [{0}]");
        put("action.stop.desc", "Stop currently running application / pipeline [{0}]", "Остановить работающее приложение / пайплайн [{0}]");
        put("action.rebuild_restart", "Rebuild & Restart [{0}]", "Пересобрать и перезапустить [{0}]");
        put("action.rebuild_restart.desc", "Force rebuild and restart application with profile '{0}'", "Принудительно пересобрать и перезапустить приложение с профилем '{0}'");
        put("action.run_restart", "Run / Restart Profile [{0}]", "Запустить / перезапустить профиль [{0}]");
        put("action.run_restart.desc", "Launch application with active profile '{0}'", "Запустить приложение с активным профилем '{0}'");
        put("action.header.spring_configs", "Configurations ({0})", "Конфигурации ({0})");
        put("action.header.actions", "Actions", "Действия");
        put("action.no_configs", "No application*.yml files found", "Файлы application*.yml не найдены");
        put("action.no_configs.desc", "No configuration files detected in resources", "В ресурсах не найдено конфигурационных файлов");
        put("action.revert_patches", "Revert Applied Git Patches ({0} active)", "Откатить примененные Git-патчи (активно: {0})");
        put("action.revert_patches.desc", "Revert temporary patches from working directory now", "Откатить временные патчи из рабочей директории сейчас");
        put("action.reload_plugin", "Reload Plugin from Disk", "Перезагрузить плагин с диска");
        put("action.reload_plugin.desc", "Dynamically reloads the latest plugin build from disk without restarting IDE", "Динамически перезагружает сборку плагина с диска без перезапуска IDE");
        put("action.settings", "Settings...", "Настройки...");
        put("action.settings.desc", "Open configuration settings", "Открыть настройки конфигураций");
        put("action.diff.title", "Compare with Base (application.yml)...", "Сравнить с базовым (application.yml)...");
        put("action.diff.desc", "Compare this configuration with the default application.yml", "Сравнить данную конфигурацию с базовым application.yml");

        // Bottom Menu Actions
        put("action.log_analyzer", "Log & Error Analyzer", "Анализатор логов и ошибок");
        put("action.log_analyzer.desc",
                "View real-time error analysis, logs, and GraphQL Mesh requests",
                "Просмотр анализа ошибок, логов и запросов GraphQL Mesh в реальном времени");
        put("action.open_log_folder", "Open Log Folder", "Открыть папку логов");
        put("action.open_log_folder.desc",
                "Open directory containing session.log and diagnostics in file manager",
                "Открыть папку с session.log и диагностикой в проводнике");
        put("action.settings_dialog", "Configuration Settings...", "Настройки конфигураций...");
        put("action.settings_dialog.desc",
                "Open configuration settings dialog",
                "Открыть окно настроек конфигураций");

        // ==========================================
        // Log & Error Analyzer Tool Window
        // ==========================================
        // Tab titles
        put("mesh.tab.live_requests", "Live Requests", "Живые запросы");
        put("mesh.tab.error_analysis", "Error Analysis", "Анализ ошибок");
        put("mesh.tab.log_scanner", "Log Scanner", "Сканер логов");

        // Toolbar buttons – MeshInspectorPanel
        put("mesh.btn.scan_log", "Scan session.log", "Сканировать session.log");
        put("mesh.btn.paste_log", "Paste Log", "Вставить лог");
        put("mesh.btn.paste_logs", "Paste Logs", "Вставить логи");
        put("mesh.btn.clear", "Clear", "Очистить");
        put("mesh.btn.pause", "Pause", "Пауза");
        put("mesh.btn.resume", "Resume", "Продолжить");
        put("mesh.btn.export_json", "Export JSON", "Экспорт JSON");
        put("mesh.btn.log_folder", "Log Folder", "Папка логов");
        put("mesh.btn.refresh", "Refresh", "Обновить");
        put("mesh.btn.copy_all", "Copy All", "Копировать всё");
        put("mesh.btn.copy_query", "Copy Query", "Копировать запрос");
        put("mesh.btn.copy_curl", "Copy as cURL", "Копировать как cURL");
        put("mesh.btn.copy_response", "Copy JSON", "Копировать JSON");
        put("mesh.btn.copy_variables", "Copy Variables", "Копировать переменные");
        put("mesh.btn.copy_logs", "Copy Raw Logs", "Копировать логи");
        put("mesh.btn.copy_stack", "Copy Stack", "Копировать стек");
        put("mesh.btn.copy_cause", "Copy Cause", "Копировать причину");
        put("mesh.btn.copy_error_report", "Copy Error Report", "Копировать отчёт об ошибке");
        put("mesh.btn.export_report", "Export Report", "Экспорт отчёта");
        put("mesh.btn.compare_success", "Compare with Success", "Сравнить с успешным");
        put("mesh.btn.search_web", "Search Web", "Поиск в сети");
        put("mesh.btn.jump_app", "Jump to App Source", "Перейти к исходнику");
        put("mesh.btn.jump_line", "Jump to Line", "Перейти к строке");
        put("mesh.btn.analyze_in_studio", "Analyze in Error Studio", "Анализировать в Error Studio");
        put("mesh.btn.previous_log", "Previous Log", "Предыдущий лог");
        put("mesh.btn.current_log", "Current Log", "Текущий лог");
        put("mesh.btn.open_in_mesh", "Open in Mesh", "Открыть в Mesh");

        // Checkboxes
        put("mesh.chk.auto_scroll", "Auto-scroll", "Авто-прокрутка");

        // Labels / combobox items
        put("mesh.label.level", "Level:", "Уровень:");
        put("mesh.label.type", "Type:", "Тип:");
        put("mesh.label.status", "Status:", "Статус:");
        put("mesh.label.category", "Category:", "Категория:");
        put("mesh.label.error_types", "Error Types:", "Типы ошибок:");
        put("mesh.filter.all_levels", "All Levels", "Все уровни");
        put("mesh.filter.all_types", "All Types", "Все типы");
        put("mesh.filter.all_requests", "All Requests", "Все запросы");
        put("mesh.filter.all_errors", "All Errors", "Все ошибки");
        put("mesh.filter.ok", "OK", "OK");
        put("mesh.filter.success", "OK", "OK");
        put("mesh.filter.pending", "Pending", "Ожидание");
        put("mesh.filter.gql_server", "GraphQL Server Error", "GraphQL ошибка сервера");
        put("mesh.filter.gql_validation", "GraphQL Validation Error", "GraphQL ошибка валидации");
        put("mesh.filter.http5xx", "HTTP 5xx (Server Error)", "HTTP 5xx (ошибка сервера)");
        put("mesh.filter.http4xx", "HTTP 4xx (Client Error)", "HTTP 4xx (ошибка клиента)");
        put("mesh.filter.timeout", "Network Timeout", "Таймаут сети");
        put("mesh.filter.app_exception", "App Exception", "Исключение приложения");

        // Empty state dashboard
        put("mesh.empty.title", "GraphQL Mesh & Error Diagnostic Studio", "Анализатор GraphQL Mesh и ошибок");
        put("mesh.empty.subtitle", "Select a request from the table, or scan logs to diagnose errors.", "Выберите запрос из таблицы или сканируйте логи для диагностики ошибок.");
        put("mesh.empty.scan_btn", "Scan session.log", "Сканировать session.log");
        put("mesh.empty.paste_btn", "Paste Log Snippet", "Вставить фрагмент лога");
        put("mesh.empty.folder_btn", "Log Folder", "Папка логов");

        // Detail panel tabs
        put("mesh.detail.tab.error", "Error Diagnostic", "Диагностика ошибок");
        put("mesh.detail.tab.error_active", "Error Diagnostic (!)", "Диагностика ошибок (!)");
        put("mesh.detail.tab.diag_ok", "Diagnostic (OK)", "Диагностика (OK)");
        put("mesh.detail.tab.query", "Query", "Запрос");
        put("mesh.detail.tab.response", "Response", "Ответ");
        put("mesh.detail.tab.variables", "Variables", "Переменные");
        put("mesh.detail.tab.trace", "Trace & Metadata", "Трассировка и метаданные");
        put("mesh.detail.tab.raw_logs", "Raw Logs", "Необработанные логи");

        // Error detail panel
        put("mesh.detail.root_cause_title", "Root Cause Message", "Сообщение о причине ошибки");
        put("mesh.detail.stack_trace_title",
                "Interactive Stack Trace & Source Navigation (Double-click to open in Editor)",
                "Интерактивный стек трейс (двойной клик — открыть в редакторе)");
        put("mesh.detail.ok_text", "No errors detected for this request", "Ошибок для этого запроса не обнаружено");
        put("mesh.detail.ok_sub", "GraphQL operation and HTTP response succeeded with 200 OK.", "Операция GraphQL и ответ HTTP вернули 200 OK.");
        put("mesh.detail.no_stack", "No stack trace captured for this request.", "Стек трейс для этого запроса не захвачен.");

        // Error analysis panel
        put("mesh.error.search_hint", "Search error cause, code, path, operation, stack trace...", "Поиск по причине, коду, пути, операции, стеку...");
        put("mesh.error.chip_all", "All Errors", "Все ошибки");
        put("mesh.error.chip_gql", "GraphQL", "GraphQL");
        put("mesh.error.chip_validation", "Validation", "Валидация");
        put("mesh.error.chip_http5xx", "HTTP 5xx", "HTTP 5xx");
        put("mesh.error.chip_http4xx", "HTTP 4xx", "HTTP 4xx");
        put("mesh.error.chip_timeout", "Timeout", "Таймаут");
        put("mesh.error.chip_app", "App Exception", "Исключение");

        // Log scanner panel
        put("mesh.log.search_hint", "Search log lines...", "Поиск по строкам лога...");
        put("mesh.log.file_info_empty", "Log file: -", "Лог-файл: -");

        // Summary bar
        put("mesh.summary.format", "Total: {0} | OK: {1} | Errors: {2}", "Всего: {0} | OK: {1} | Ошибок: {2}");
        put("mesh.summary.gql_http_timeout", " (GQL: {0}, HTTP: {1}, Timeout: {2})", " (GQL: {0}, HTTP: {1}, Таймаут: {2})");
        put("mesh.summary.avg_latency", " | Avg Latency: {0} ms", " | Ср. задержка: {0} мс");

        // Error analysis empty state
        put("mesh.error.empty_title", "No Errors Captured in Current Session", "В текущей сессии ошибок не обнаружено");
        put("mesh.error.empty_sub",
                "All executed GraphQL requests succeeded, or the application hasn't run yet.",
                "Все выполненные GraphQL-запросы завершились успешно, либо приложение еще не запускалось.");
        put("mesh.error.empty_scan_btn", "Scan session.log for Errors", "Сканировать session.log на ошибки");
        put("mesh.error.empty_paste_btn", "Paste Logs to Analyze", "Вставить логи для анализа");
        put("mesh.error.empty_folder_btn", "Open Log Folder", "Открыть папку логов");

        // Table column headers
        put("mesh.table.col.num", "#", "#");
        put("mesh.table.col.time", "Time", "Время");
        put("mesh.table.col.level", "Level", "Уровень");
        put("mesh.table.col.status", "Status", "Статус");
        put("mesh.table.col.http", "HTTP", "HTTP");
        put("mesh.table.col.type", "Type", "Тип");
        put("mesh.table.col.operation", "Operation", "Операция");
        put("mesh.table.col.latency", "Latency", "Задержка");
        put("mesh.table.col.trace_id", "Trace ID", "Trace ID");
        put("mesh.table.col.size", "Size", "Размер");

        // Paste Log Dialog
        put("mesh.dialog.paste.title", "Paste Logs for GraphQL & Error Analysis", "Вставка логов для анализа ошибок");
        put("mesh.dialog.paste.button", "Analyze Logs", "Анализировать логи");
        put("mesh.dialog.paste.instruction",
                "Paste raw console logs, GraphQL requests/responses, or stack traces below:",
                "Вставьте логи консоли, запросы/ответы GraphQL или стек-трейсы ниже:");

        // Log Analyzer button tooltips
        put("mesh.search.placeholder",
                "Filter by query, operation, response, trace...",
                "Поиск по запросу, операции, ответу, trace...");
        put("mesh.btn.open_in_mesh.tooltip",
                "Open selected Mesh web address in browser and run GraphQL query via Chrome extension",
                "Открыть выбранный адрес Mesh в браузере и выполнить GraphQL-запрос через расширение Chrome");
        put("mesh.btn.scan_log.tooltip",
                "Scan session.log to parse queries and errors",
                "Сканировать session.log для разбора запросов и ошибок");
        put("mesh.btn.paste_log.tooltip",
                "Paste raw log snippets or stack traces to analyze",
                "Вставить фрагмент лога или стек-трейс для анализа");
        put("mesh.btn.clear.tooltip",
                "Clear all captured entries",
                "Очистить все захваченные записи");
        put("mesh.btn.pause.tooltip",
                "Pause capturing new requests from logs",
                "Приостановить захват новых запросов из логов");
        put("mesh.btn.resume.tooltip",
                "Resume capturing requests from logs",
                "Возобновить захват запросов из логов");
        put("mesh.btn.export_json.tooltip",
                "Export captured sessions to JSON clipboard",
                "Экспорт захваченных запросов в буфер обмена (JSON)");
        put("mesh.btn.log_folder.tooltip",
                "Open folder containing session.log in file manager",
                "Открыть папку с session.log в проводнике");
        put("mesh.btn.errors_only.tooltip",
                "Filter table to show only errors",
                "Показывать в таблице только ошибки");
        put("mesh.btn.mesh_only.tooltip",
                "Filter table to show only QUERY and MUTATION records",
                "Показывать в таблице только записи QUERY и MUTATION");
        put("mesh.btn.copy_query.tooltip",
                "Copy GraphQL query text to clipboard",
                "Копировать текст GraphQL-запроса в буфер");
        put("mesh.btn.copy_curl.tooltip",
                "Generate and copy cURL command",
                "Сформировать и скопировать команду cURL");
        put("mesh.btn.copy_response.tooltip",
                "Copy response JSON to clipboard",
                "Копировать JSON-ответ в буфер");
        put("mesh.btn.copy_variables.tooltip",
                "Copy variables JSON to clipboard",
                "Копировать JSON переменных в буфер");
        put("mesh.btn.copy_logs.tooltip",
                "Copy raw log lines for this request",
                "Копировать строки лога для этого запроса");
        put("mesh.btn.copy_error_report.tooltip",
                "Copy structured Markdown error report for Jira/Slack",
                "Копировать структурированный отчёт об ошибке в Markdown для Jira/Slack");
        put("mesh.btn.compare_success.tooltip",
                "Compare query and variables with previous successful request",
                "Сравнить запрос и переменные с предыдущим успешным запросом");
        put("mesh.btn.search_web.tooltip",
                "Search root cause in Google",
                "Искать причину ошибки в Google");
        put("mesh.btn.copy_cause.tooltip",
                "Copy root cause message to clipboard",
                "Копировать причину ошибки в буфер");
        put("mesh.btn.jump_app.tooltip",
                "Open the first application code line in editor",
                "Открыть первую строку кода приложения в редакторе");
        put("mesh.btn.jump_line.tooltip",
                "Open selected stack line in editor",
                "Открыть выбранную строку стека в редакторе");
        put("mesh.btn.copy_stack.tooltip",
                "Copy full stack trace to clipboard",
                "Копировать полный стек-трейс в буфер");
        put("mesh.btn.analyze_in_studio.tooltip",
                "Feed all log lines into Error Diagnostic Studio",
                "Передать все строки лога в Студию диагностики ошибок");
        put("mesh.btn.refresh.tooltip",
                "Reload log file from disk",
                "Перезагрузить файл лога с диска");
        put("mesh.btn.previous_log.tooltip",
                "Toggle between current session.log and rotated session.prev.log",
                "Переключить между session.log и предыдущим session.prev.log");
        put("mesh.btn.paste_logs.tooltip",
                "Paste raw log snippets to analyze",
                "Вставить фрагмент лога для анализа");
        put("mesh.btn.copy_all.tooltip",
                "Copy all log text to clipboard",
                "Копировать весь текст лога в буфер");
        put("mesh.btn.export_report.tooltip",
                "Export full Markdown error report of all captured errors",
                "Экспорт сводного Markdown-отчёта по всем ошибкам");
        put("mesh.btn.add_address.tooltip",
                "Add and store new Mesh web address",
                "Добавить и сохранить новый адрес Mesh");
        put("mesh.btn.remove_address.tooltip",
                "Remove selected Mesh web address",
                "Удалить выбранный адрес Mesh");

        // Help Tooltips ('?' Blue Icon) for Log Analyzer
        put("help.mesh.chrome_plugin",
                "<b>GraphiQL Links (Chrome Extension)</b><br>Opens this GraphQL query in a new editor tab in GraphiQL web console and executes it automatically.<br><br><b>Installation in Google Chrome:</b><br>1. Open <code>chrome://extensions</code> and enable <b>Developer mode</b>.<br>2. Click <b>Load unpacked</b> and select the <code>chrome-mesh-extension</code> folder in the project root.<br>3. The extension automatically redirects <code>?query=</code> to <code>#query=</code> (preventing Nginx 414 errors) and fills Query & Variables via CodeMirror 5 API.<br><br><b>Supported hosts:</b><br>Configured in extension <code>manifest.json</code> (e.g. <code>https://&lt;graphiql-host&gt;/graphiql/*</code>).",
                "<b>Расширение Chrome: GraphiQL Links</b><br>Открывает данный GraphQL-запрос в новой вкладке веб-консоли GraphiQL и автоматически выполняет его.<br><br><b>Установка в Google Chrome:</b><br>1. Откройте <code>chrome://extensions</code> и включите <b>Режим разработчика</b>.<br>2. Нажмите <b>Загрузить распакованное расширение</b> и выберите папку <code>chrome-mesh-extension</code> из корня проекта.<br>3. Расширение на лету перенаправляет <code>?query=</code> в <code>#query=</code> (защита от ошибки Nginx 414) и заполняет запрос и переменные через CodeMirror 5 API.<br><br><b>Поддерживаемые адреса:</b><br>Настраиваются в <code>manifest.json</code> расширения (например, <code>https://&lt;graphiql-host&gt;/graphiql/*</code>).");
        put("help.mesh.address",
                "<b>GraphQL Mesh Web Console</b><br>Select or enter the base HTTP/HTTPS URL of your GraphQL Mesh console.<br>Use <b>+</b> to add a new address and <b>−</b> to remove.",
                "<b>Веб-консоль GraphQL Mesh</b><br>Выберите или введите базовый URL-адрес консоли GraphQL Mesh.<br>Используйте <b>+</b> для добавления нового адреса и <b>−</b> для удаления.");
        put("help.mesh.inspector_filters",
                "<b>Live Request Filters</b><br>• <b>Level</b>: filter by log level (ERROR, WARN, INFO, DEBUG).<br>• <b>Type</b>: filter by GraphQL operation (QUERY, MUTATION).<br>• <b>Status</b>: filter by execution result (Errors, Success, Timeouts).",
                "<b>Фильтры живых запросов</b><br>• <b>Уровень</b>: фильтрация по уровню лога (ERROR, WARN, INFO, DEBUG).<br>• <b>Тип</b>: фильтрация по типу операции (QUERY, MUTATION).<br>• <b>Статус</b>: фильтрация по результату (Ошибки, Успешные, Таймауты).");
        put("help.mesh.inspector_actions",
                "<b>Analyzer Actions</b><br>• <b>Scan session.log</b>: parse current run log into GraphQL and error entries.<br>• <b>Paste Log</b>: paste arbitrary log snippet or stack trace for diagnosis.<br>• <b>Auto-scroll</b>: automatically follow incoming live requests.<br>• <b>Export JSON</b>: copy captured session requests to clipboard.",
                "<b>Действия анализатора</b><br>• <b>Сканировать session.log</b>: распарсить лог текущей сессии на GraphQL-запросы и ошибки.<br>• <b>Вставить лог</b>: вставить произвольный фрагмент лога или стек-трейс для анализа.<br>• <b>Авто-прокрутка</b>: автоматически следовать за новыми входящими запросами.<br>• <b>Экспорт JSON</b>: скопировать историю запросов в буфер обмена в формате JSON.");
        put("help.mesh.error_categories",
                "<b>Error Categorization Studio</b><br>Automatically classifies application and network issues:<br>• <b>GraphQL Server</b>: resolver execution failure.<br>• <b>Validation</b>: schema/syntax mismatch.<br>• <b>HTTP 5xx</b>: backend server failure.<br>• <b>HTTP 4xx</b>: client or bad request error.<br>• <b>Timeout</b>: network connection timeout.<br>• <b>App Exception</b>: internal Java runtime exceptions.",
                "<b>Студия классификации ошибок</b><br>Автоматически определяет категорию сбоя:<br>• <b>GraphQL Server</b>: сбой выполнения резолвера.<br>• <b>Валидация</b>: несоответствие схемы или синтаксиса.<br>• <b>HTTP 5xx</b>: серверная ошибка бэкенда.<br>• <b>HTTP 4xx</b>: клиентская ошибка или неверный запрос.<br>• <b>Таймаут</b>: таймаут сетевого подключения.<br>• <b>Исключение</b>: внутреннее Java-исключение сервиса.");
        put("help.mesh.log_scanner",
                "<b>Session Log Scanner</b><br>Inspects raw application logs from <code>session.log</code> and <code>session.prev.log</code>.<br>• <b>Analyze in Error Studio</b>: feeds all lines to the error classification engine.<br>• <b>Previous Log</b>: switches to the previous run log after restarts.",
                "<b>Сканер логов сессии</b><br>Просмотр сырого вывода приложения из <code>session.log</code> и <code>session.prev.log</code>.<br>• <b>Анализировать в Error Studio</b>: передать все строки лога в классификатор ошибок.<br>• <b>Предыдущий лог</b>: переключение на лог предыдущего запуска после перезапуска.");

        // ==========================================
        // Help Tooltips ('?' Blue Icon)
        // ==========================================
        put("help.language",
                "Select interface language (English or Russian).\nSwitches immediately across all dialogs, tables, and toolbar actions.",
                "Выберите язык интерфейса плагина (English или Русский).\nЯзык переключается мгновенно во всех окнах, таблицах и действиях тулбара.");
        put("help.patches.table",
                "List of Git patches (.patch / .diff) applied to your working directory before the build stage, and auto-reverted immediately after build finishes.\n• Check 'Active' to include in the pipeline.\n• Use 'Paste from Clipboard...' to create patches from git diff.",
                "Список Git-патчей (.patch / .diff), автоматически применяемых к рабочей копии перед этапом сборки и автоматически откатываемых сразу после завершения сборки.\n• Отметьте 'Активен', чтобы включить патч в пайплайн.\n• Нажмите 'Вставить из буфера...', чтобы создать патч из git diff.");
        put("help.patches.revert_before_apply",
                "Discards any local uncommitted/dirty changes in files targeted by git patches (git checkout -- <file>) to ensure patches apply cleanly without merge conflicts.",
                "Сбрасывает любые локальные незакоммиченные изменения в целевых файлах патча (git checkout -- <файл>), гарантируя чистое применение без конфликтов.");
        put("help.patches.auto_revert",
                "Automatically reverts all applied git patches in reverse order (git apply -R) immediately when the pre-run build finishes, keeping your working tree clean while the application runs.",
                "Автоматически откатывает все примененные git-патчи в обратном порядке (git apply -R) сразу по окончании сборки, сохраняя рабочую копию чистой во время работы приложения.");
        put("help.patches.storage_folder",
                "Relative folder in the project where created git patch files (.patch) are saved (default: .idea/patches).",
                "Относительный путь к папке в проекте, где сохраняются созданные файлы git-патчей (.patch) (по умолчанию: .idea/patches).");
        put("help.build.enable",
                "Enable running an automated build command (e.g. Maven or Gradle) after applying patches and before launching the Spring Boot application.",
                "Включить автоматический запуск команды сборки (например, Maven или Gradle) после применения патчей и перед запуском приложения Spring Boot.");
        put("help.build.command",
                "Command executed during the build stage.\nExample: mvn -T 8 -o \"-Dmaven.test.skip=true\"\nExecuted in project root.",
                "Команда, выполняемая на этапе сборки.\nПример: mvn -T 8 -o \"-Dmaven.test.skip=true\"\nВыполняется в корне проекта.");
        put("help.build.skip_if_running",
                "If the application is already running and you switch profiles, skip the time-consuming pre-run build and quickly launch the newly selected profile.",
                "Если приложение уже работает и вы переключаете профиль, пропустить длительную предварительную сборку и сразу перезапустить выбранный профиль.");
        put("help.run.template",
                "Template for launching the application in Terminal.\nPlaceholders:\n• {profile} — selected Spring profile name\n• {filePath} — path to configuration YAML\nExample:\njava -jar target/server.jar --n2o.config.path=src/main/resources/META-INF/conf --spring.profiles.active={profile},auth-dev",
                "Шаблон команды для запуска приложения в терминале.\nПодстановки:\n• {profile} — имя выбранного Spring-профиля\n• {filePath} — путь к YAML-конфигурации\nПример:\njava -jar target/server.jar --n2o.config.path=src/main/resources/META-INF/conf --spring.profiles.active={profile},auth-dev");
        put("help.run.recalc",
                "Scans project modules and automatically detects the target JAR file and N2O config path to generate the optimal command line.",
                "Сканирует модули проекта и автоматически находит целевой JAR-файл и путь к конфигурации N2O, формируя оптимальную команду.");
        put("help.run.log_levels",
                "Filter log messages written to the terminal console by log severity (DEBUG, INFO, WARN, ERROR). All logs are still preserved in session.log.",
                "Фильтрация сообщений, выводимых в консоль терминала, по уровням логирования (DEBUG, INFO, WARN, ERROR). Все логи сохраняются в session.log.");
        put("help.run.colorize_error",
                "Highlights lines containing ERROR, Exception, and Caused by in bright ANSI red in the terminal console.",
                "Подсвечивает строки с ошибками (ERROR, Exception, Caused by) ярко-красным цветом ANSI в терминале.");
        put("help.run.call_stack",
                "Toggle Java/Tomcat stack trace lines ('at com.example...') in terminal output.\nUnchecked (recommended): hides noisy stack frames while preserving the root cause in bright red.",
                "Управление выводом строк трассировки стека вызовов Java/Tomcat ('at com.example...') в терминале.\nВыключено (рекомендуется): скрывает длинные портянки стека, оставляя причину ошибки ярко-красной.");
        put("help.mesh.enable",
                "Intercepts and categorizes GraphQL Mesh and HTTP requests in real-time with latency, headers, variables, JSON formatting, and cURL export.",
                "Перехватывает и анализирует запросы и ответы GraphQL Mesh в реальном времени с измерением задержки, заголовками, форматированием JSON и экспортом в cURL.");
        put("help.mesh.endpoint",
                "Default HTTP URL for GraphQL Mesh / GraphiQL service (e.g. http://localhost:8080/graphiql/).",
                "URL-адрес по умолчанию для сервиса GraphQL Mesh / GraphiQL (например, http://localhost:8080/graphiql/).");
        put("help.mesh.max_history",
                "Maximum number of captured GraphQL Mesh requests kept in memory before older entries are rotated.",
                "Максимальное количество сохраненных запросов GraphQL Mesh в памяти перед ротацией старых записей.");
        put("help.logs.diagnostics",
                "Plugin logs, process execution history, and session.log are stored in this directory. Click 'Open Log Folder' to inspect.",
                "Логи работы плагина, история выполнения процессов и session.log хранятся в этой директории. Нажмите 'Открыть папку логов' для просмотра.");
    }

    private static void put(String key, String enVal, String ruVal) {
        EN.put(key, enVal);
        RU.put(key, ruVal);
    }

    private I18n() {}

    @NotNull
    public static String get(@NotNull PluginLanguage lang, @NotNull String key, Object... args) {
        Map<String, String> dict = (lang == PluginLanguage.RU) ? RU : EN;
        String val = dict.get(key);
        if (val == null) {
            val = EN.get(key);
        }
        if (val == null) {
            val = key;
        }
        return format(val, args);
    }

    @NotNull
    public static String get(@Nullable Project project, @NotNull String key, Object... args) {
        PluginLanguage lang = PluginLanguage.EN;
        if (project != null && !project.isDisposed()) {
            try {
                PluginSettingsState st = PluginSettingsState.getInstance(project);
                if (st != null && st.getState().language != null) {
                    lang = st.getState().language;
                }
            } catch (Exception ignored) {}
        }
        return get(lang, key, args);
    }

    @NotNull
    public static String format(@Nullable String pattern, Object... args) {
        if (pattern == null) return "";
        if (args == null || args.length == 0 || !pattern.contains("{")) {
            return pattern;
        }
        String res = pattern;
        for (int i = 0; i < args.length; i++) {
            res = res.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return res;
    }
}
