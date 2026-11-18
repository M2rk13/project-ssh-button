package ru.projectsshbutton;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int OPEN_PRIVATE_KEY = 1001;
    private static final String PREFERENCES = "ssh_profiles";
    private static final String LEGACY_PROFILES = "profiles";
    private static final String SERVERS = "servers";
    private static final String COMMANDS = "commands";
    private static final String SELECTED_SERVER = "selected_server";
    private static final String DARK_THEME = "dark_theme";
    private static final int DEFAULT_PORT = 22;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MAX_PRIVATE_KEY_BYTES = 1_048_576;

    private final ExecutorService sshExecutor = Executors.newSingleThreadExecutor();
    private final List<Server> servers = new ArrayList<>();
    private final List<CommandButton> commands = new ArrayList<>();
    private SharedPreferences preferences;
    private boolean darkTheme;
    private int backgroundColor, surfaceColor, textColor, mutedTextColor, accentColor, accentSoftColor;
    private LinearLayout content, mainPanel, editorPanel;
    private GridLayout commandGrid;
    private Spinner serverSpinner;
    private ArrayAdapter<String> serverAdapter;
    private TextView terminalView;
    private EditText serverNameField, hostField, portField, userField, keyField, timeoutField, commandNameField, commandField;
    private Uri selectedKeyUri;
    private String editingServerId, editingCommandId;
    private boolean selectingServer;

    @Override public void onCreate(Bundle state) {
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        darkTheme = preferences.getBoolean(DARK_THEME, true);
        setTheme(darkTheme ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(state);
        configureWindow(); loadDataAndMigrate(); createView(); refreshServerSpinner(); renderCommands();
    }

    @Override protected void onDestroy() { sshExecutor.shutdownNow(); super.onDestroy(); }

    private void configureWindow() {
        setPalette(); Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 35) window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(backgroundColor); window.setNavigationBarColor(backgroundColor);
        if (Build.VERSION.SDK_INT >= 23) window.getDecorView().setSystemUiVisibility(darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void setPalette() {
        if (darkTheme) {
            backgroundColor = Color.rgb(11, 10, 16); surfaceColor = Color.rgb(28, 20, 28); textColor = Color.rgb(250, 242, 246);
            mutedTextColor = Color.rgb(198, 178, 187); accentColor = Color.rgb(255, 48, 86); accentSoftColor = Color.rgb(93, 24, 40);
        } else {
            backgroundColor = Color.rgb(244, 252, 255); surfaceColor = Color.WHITE; textColor = Color.rgb(9, 25, 35);
            mutedTextColor = Color.rgb(52, 91, 110); accentColor = Color.rgb(0, 150, 216); accentSoftColor = Color.rgb(205, 243, 255);
        }
    }

    private void createView() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(backgroundColor);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); int padding = dp(16); content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> { if (Build.VERSION.SDK_INT >= 35) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); content.setPadding(padding, padding + bars.top, padding, padding + bars.bottom); } return insets; });
        TextView title = text("PROJECT SSH BUTTON", 22, accentColor, true); title.setLetterSpacing(.08f); content.addView(title, fullWidth());
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.addView(text("Сервер", 13, mutedTextColor, false), new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        serverSpinner = new Spinner(this); serverAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>()); serverSpinner.setAdapter(serverAdapter);
        serverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onNothingSelected(AdapterView<?> p) { } @Override public void onItemSelected(AdapterView<?> p, View view, int position, long id) { if (!selectingServer && position > 0) selectServer(servers.get(position - 1).id); } });
        top.addView(serverSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); top.addView(button(darkTheme ? "☼" : "☾", v -> switchTheme()), wrap()); content.addView(top, fullWidth());
        mainPanel = new LinearLayout(this); mainPanel.setOrientation(LinearLayout.VERTICAL);
        terminalView = text("$ Выберите или добавьте сервер", 14, textColor, false); terminalView.setTypeface(Typeface.MONOSPACE); terminalView.setTextIsSelectable(true); terminalView.setPadding(dp(12), dp(12), dp(12), dp(12)); terminalView.setBackground(panelBackground());
        ScrollView terminal = new ScrollView(this); terminal.addView(terminalView, fullWidth()); mainPanel.addView(terminal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        commandGrid = new GridLayout(this); commandGrid.setUseDefaultMargins(true); commandGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS); mainPanel.addView(commandGrid, fullWidth());
        LinearLayout manage = new LinearLayout(this); manage.setOrientation(LinearLayout.HORIZONTAL); manage.addView(button("+ Сервер", v -> editServer(null)), weight()); manage.addView(button("Сервер", v -> editServer(selectedServer())), weight()); manage.addView(button("+ Кнопка", v -> editCommand(null)), weight()); mainPanel.addView(manage, fullWidth()); content.addView(mainPanel, fullWidth());
        editorPanel = new LinearLayout(this); editorPanel.setOrientation(LinearLayout.VERTICAL); content.addView(editorPanel, fullWidth()); setContentView(scroll); showMain();
    }

    private void switchTheme() { preferences.edit().putBoolean(DARK_THEME, !darkTheme).apply(); recreate(); }

    private void editServer(Server server) {
        editingServerId = server == null ? null : server.id; editingCommandId = null; editorPanel.removeAllViews(); editorPanel.addView(text(server == null ? "НОВЫЙ СЕРВЕР" : "НАСТРОЙКИ СЕРВЕРА", 20, accentColor, true), fullWidth());
        serverNameField = input("Название сервера", "Production"); hostField = input("Хост", "ssh.example.com"); portField = input("Порт", "22"); portField.setInputType(InputType.TYPE_CLASS_NUMBER); userField = input("Пользователь", "deploy");
        addLabel("Приватный SSH-ключ"); LinearLayout keyRow = new LinearLayout(this); keyField = inputField("Ключ не выбран"); keyField.setFocusable(false); keyRow.addView(keyField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); keyRow.addView(button("Выбрать", v -> choosePrivateKey()), wrap()); editorPanel.addView(keyRow, fullWidth()); addHint("При первом запуске подтвердите показанный ключ сервера."); timeoutField = input("Тайм-аут, секунд", "15"); timeoutField.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (server != null) { serverNameField.setText(server.name); hostField.setText(server.host); portField.setText(String.valueOf(server.port)); userField.setText(server.user); timeoutField.setText(String.valueOf(server.timeoutSeconds)); selectedKeyUri = Uri.parse(server.keyUri); keyField.setText(displayName(selectedKeyUri)); } else selectedKeyUri = null;
        LinearLayout actions = new LinearLayout(this); actions.addView(button("Сохранить", v -> saveServer()), weight()); if (server != null) actions.addView(button("Удалить", v -> deleteServer(server)), weight()); actions.addView(button("Назад", v -> showMain()), weight()); editorPanel.addView(actions, fullWidth()); showEditor();
    }

    private void editCommand(CommandButton command) {
        Server server = selectedServer(); if (server == null) { showTerminal("$ Сначала добавьте сервер."); return; }
        editingCommandId = command == null ? null : command.id; editingServerId = null; editorPanel.removeAllViews(); editorPanel.addView(text(command == null ? "НОВАЯ КНОПКА" : "НАСТРОЙКИ КНОПКИ", 20, accentColor, true), fullWidth()); addHint("Сервер: " + server.name);
        commandNameField = input("Название на кнопке", "Перезапустить сервис"); commandField = input("Команда", "systemctl restart my-service"); commandField.setMinLines(4); commandField.setGravity(Gravity.TOP); if (command != null) { commandNameField.setText(command.name); commandField.setText(command.command); }
        LinearLayout actions = new LinearLayout(this); actions.addView(button("Сохранить", v -> saveCommand()), weight()); if (command != null) actions.addView(button("Удалить", v -> deleteCommand(command)), weight()); actions.addView(button("Назад", v -> showMain()), weight()); editorPanel.addView(actions, fullWidth()); showEditor();
    }

    private void saveServer() {
        try {
            String name = required(serverNameField, "Название сервера"), host = required(hostField, "Хост"), user = required(userField, "Пользователь"); if (selectedKeyUri == null) throw new IllegalArgumentException("Выберите приватный ключ."); int port = number(portField, "Порт", 1, 65535), timeout = number(timeoutField, "Тайм-аут", 1, 300);
            Server prior = editingServerId == null ? null : serverById(editingServerId); String pin = prior != null && prior.host.equals(host) && prior.port == port ? prior.hostFingerprint : ""; Server saved = new Server(prior == null ? UUID.randomUUID().toString() : prior.id, name, host, port, user, selectedKeyUri.toString(), pin, timeout); replaceServer(saved); selectServer(saved.id); persistData(); showMain();
        } catch (IllegalArgumentException e) { showTerminal("$ " + e.getMessage()); }
    }

    private void saveCommand() {
        try {
            Server server = selectedServer(); String name = required(commandNameField, "Название кнопки"), line = required(commandField, "Команда"); CommandButton old = editingCommandId == null ? null : commandById(editingCommandId); CommandButton saved = new CommandButton(old == null ? UUID.randomUUID().toString() : old.id, server.id, name, line); if (old != null) commands.remove(old); commands.add(saved); persistData(); renderCommands(); showMain();
        } catch (IllegalArgumentException e) { showTerminal("$ " + e.getMessage()); }
    }

    private void deleteServer(Server server) { new AlertDialog.Builder(this).setTitle("Удалить сервер?").setMessage("Его кнопки тоже будут удалены.").setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> { servers.remove(server); commands.removeIf(c -> c.serverId.equals(server.id)); preferences.edit().remove(SELECTED_SERVER).apply(); persistData(); refreshServerSpinner(); renderCommands(); showMain(); }).show(); }
    private void deleteCommand(CommandButton command) { commands.remove(command); persistData(); renderCommands(); showMain(); }

    private void renderCommands() {
        if (commandGrid == null) return; commandGrid.removeAllViews(); int columns = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 4 : 2; commandGrid.setColumnCount(columns); Server server = selectedServer(); if (server == null) return;
        for (CommandButton command : commands) { if (!command.serverId.equals(server.id)) continue; Button item = button(command.name, v -> requestPassphraseAndRun(server, command)); item.setAllCaps(false); item.setMinHeight(dp(76)); item.setTextSize(16); item.setTextColor(darkTheme ? Color.WHITE : Color.rgb(0, 57, 84)); item.setBackground(accentButtonBackground()); item.setOnLongClickListener(v -> { editCommand(command); return true; }); GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = 0; params.height = ViewGroup.LayoutParams.WRAP_CONTENT; params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f); params.setMargins(dp(3), dp(3), dp(3), dp(3)); commandGrid.addView(item, params); }
    }

    private void requestPassphraseAndRun(Server server, CommandButton command) { if (server.hostFingerprint.isEmpty()) inspectAndConfirmHostKey(server, command); else requestPassphrase(server, command); }
    private void inspectAndConfirmHostKey(Server server, CommandButton command) { showTerminal("$ Получаю ключ " + server.host + ":" + server.port + "…"); sshExecutor.execute(() -> { try { String fingerprint = SshTransport.inspectHostKey(request(server, command.command, "")); runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Доверять серверу?").setMessage(server.name + " сообщил ключ:\n\n" + fingerprint + "\n\nПодтвердите, если это ожидаемый сервер.").setNegativeButton("Отмена", (d, w) -> showTerminal("$ Ключ не сохранён.")).setPositiveButton("Доверять и запустить", (d, w) -> { Server pinned = server.withFingerprint(fingerprint); replaceServer(pinned); persistData(); requestPassphrase(pinned, command); }).show()); } catch (Exception e) { showTerminal("$ Ошибка получения ключа: " + safeMessage(e)); } }); }
    private void requestPassphrase(Server server, CommandButton command) { EditText field = inputField("Оставьте пустым, если ключ без пароля"); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); new AlertDialog.Builder(this).setTitle("Пароль ключа").setView(field).setNegativeButton("Отмена", null).setPositiveButton("Запустить", (d, w) -> { Editable entered = field.getText(); char[] chars = new char[entered.length()]; entered.getChars(0, entered.length(), chars, 0); entered.clear(); runCommand(server, command, chars); }).show(); }
    private void runCommand(Server server, CommandButton command, char[] passphrase) { showTerminal("$ " + server.name + " › " + command.name + "\nПодключение…"); sshExecutor.execute(() -> { byte[] key = null; try { key = readAllBytes(Uri.parse(server.keyUri)); SshTransport.Result result = SshTransport.execute(request(server, command.command, server.hostFingerprint), key, passphrase); String output = "$ exit " + result.exitStatus + "\n" + result.output; if (!result.errorOutput.isEmpty()) output += "\n[stderr]\n" + result.errorOutput; showTerminal(output); } catch (Exception e) { showTerminal("$ Ошибка: " + safeMessage(e)); } finally { Arrays.fill(passphrase, '\0'); if (key != null) Arrays.fill(key, (byte) 0); } }); }
    private SshTransport.Request request(Server server, String command, String pin) { return new SshTransport.Request(server.host, server.port, server.user, pin, command, server.timeoutSeconds); }

    private void choosePrivateKey() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, OPEN_PRIVATE_KEY); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode != OPEN_PRIVATE_KEY || resultCode != RESULT_OK || data == null || data.getData() == null) return; Uri uri = data.getData(); try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); selectedKeyUri = uri; keyField.setText(displayName(uri)); } catch (SecurityException e) { showTerminal("$ Не удалось сохранить доступ к ключу."); } }

    private void loadDataAndMigrate() { try { String savedServers = preferences.getString(SERVERS, null); if (savedServers != null) { JSONArray array = new JSONArray(savedServers); for (int i = 0; i < array.length(); i++) servers.add(Server.fromJson(array.getJSONObject(i))); } String savedCommands = preferences.getString(COMMANDS, null); if (savedCommands != null) { JSONArray array = new JSONArray(savedCommands); for (int i = 0; i < array.length(); i++) commands.add(CommandButton.fromJson(array.getJSONObject(i))); } if (savedServers == null) migrateProfiles(); } catch (JSONException e) { servers.clear(); commands.clear(); } }
    private void migrateProfiles() throws JSONException {
        JSONArray profiles = new JSONArray(preferences.getString(LEGACY_PROFILES, "[]"));
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            String host = profile.getString("host"), user = profile.getString("user"), keyUri = profile.getString("keyUri");
            int port = profile.getInt("port");
            Server server = null;
            for (Server candidate : servers) {
                if (candidate.host.equals(host) && candidate.port == port && candidate.user.equals(user) && candidate.keyUri.equals(keyUri)) { server = candidate; break; }
            }
            if (server == null) {
                server = new Server(UUID.randomUUID().toString(), profile.optString("name", host), host, port, user, keyUri, profile.optString("hostFingerprint"), profile.getInt("timeoutSeconds"));
                servers.add(server);
            } else if (server.hostFingerprint.isEmpty() && !profile.optString("hostFingerprint").isEmpty()) {
                server = server.withFingerprint(profile.optString("hostFingerprint"));
                replaceServer(server);
            }
            commands.add(new CommandButton(UUID.randomUUID().toString(), server.id, profile.optString("name", "Запустить"), profile.getString("command")));
        }
        persistData();
    }
    private void persistData() { JSONArray serverJson = new JSONArray(), commandJson = new JSONArray(); for (Server server : servers) serverJson.put(server.toJson()); for (CommandButton command : commands) commandJson.put(command.toJson()); preferences.edit().putString(SERVERS, serverJson.toString()).putString(COMMANDS, commandJson.toString()).apply(); }
    private void refreshServerSpinner() { if (serverAdapter == null) return; String selected = preferences.getString(SELECTED_SERVER, ""); selectingServer = true; serverAdapter.clear(); serverAdapter.add("Выберите сервер"); int position = 0; for (int i = 0; i < servers.size(); i++) { serverAdapter.add(servers.get(i).name); if (servers.get(i).id.equals(selected)) position = i + 1; } serverAdapter.notifyDataSetChanged(); serverSpinner.setSelection(position); selectingServer = false; }
    private void selectServer(String id) { preferences.edit().putString(SELECTED_SERVER, id).apply(); refreshServerSpinner(); renderCommands(); }
    private Server selectedServer() { return serverById(preferences.getString(SELECTED_SERVER, "")); }
    private Server serverById(String id) { for (Server server : servers) if (server.id.equals(id)) return server; return null; }
    private CommandButton commandById(String id) { for (CommandButton command : commands) if (command.id.equals(id)) return command; return null; }
    private void replaceServer(Server updated) { Server old = serverById(updated.id); if (old != null) servers.remove(old); servers.add(updated); }

    private void showMain() { editorPanel.setVisibility(View.GONE); mainPanel.setVisibility(View.VISIBLE); renderCommands(); }
    private void showEditor() { mainPanel.setVisibility(View.GONE); editorPanel.setVisibility(View.VISIBLE); }
    private void showTerminal(String value) { runOnUiThread(() -> terminalView.setText(value)); }
    private EditText input(String label, String hint) { addLabel(label); EditText field = inputField(hint); editorPanel.addView(field, fullWidth()); return field; }
    private EditText inputField(String hint) { EditText field = new EditText(this); field.setHint(hint); field.setTextColor(textColor); field.setHintTextColor(mutedTextColor); field.setSingleLine(false); return field; }
    private void addLabel(String value) { TextView label = text(value, 13, mutedTextColor, false); label.setPadding(0, dp(12), 0, 0); editorPanel.addView(label, fullWidth()); }
    private void addHint(String value) { TextView hint = text(value, 13, mutedTextColor, false); hint.setPadding(0, dp(6), 0, dp(6)); editorPanel.addView(hint, fullWidth()); }
    private TextView text(String value, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private Button button(String value, View.OnClickListener action) { Button view = new Button(this); view.setText(value); view.setTextColor(textColor); view.setAllCaps(false); view.setOnClickListener(action); view.setBackground(outlineButtonBackground()); return view; }
    private GradientDrawable panelBackground() { GradientDrawable shape = new GradientDrawable(); shape.setColor(surfaceColor); shape.setStroke(dp(1), accentSoftColor); shape.setCornerRadius(dp(10)); return shape; }
    private GradientDrawable outlineButtonBackground() { GradientDrawable shape = new GradientDrawable(); shape.setColor(surfaceColor); shape.setStroke(dp(1), accentColor); shape.setCornerRadius(dp(8)); return shape; }
    private GradientDrawable accentButtonBackground() { GradientDrawable shape = new GradientDrawable(); shape.setColor(accentSoftColor); shape.setStroke(dp(1), accentColor); shape.setCornerRadius(dp(8)); return shape; }
    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String required(EditText field, String label) { String value = field.getText().toString().trim(); if (value.isEmpty()) throw new IllegalArgumentException("Заполните поле «" + label + "»."); return value; }
    private static int number(EditText field, String label, int min, int max) { try { int value = Integer.parseInt(required(field, label)); if (value < min || value > max) throw new NumberFormatException(); return value; } catch (NumberFormatException e) { throw new IllegalArgumentException(label + " должен быть от " + min + " до " + max + "."); } }
    private byte[] readAllBytes(Uri uri) throws Exception { ContentResolver resolver = getContentResolver(); try (InputStream input = resolver.openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) { if (input == null) throw new IllegalStateException("Не удалось открыть ключ."); byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) { if (output.size() > MAX_PRIVATE_KEY_BYTES - count) throw new IllegalArgumentException("Ключ больше 1 MiB."); output.write(buffer, 0, count); } return output.toByteArray(); } }
    private String displayName(Uri uri) { try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) { if (cursor != null && cursor.moveToFirst()) { int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (column >= 0) return cursor.getString(column); } } catch (Exception ignored) { } return uri.getLastPathSegment(); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private static final class Server {
        final String id, name, host, user, keyUri, hostFingerprint; final int port, timeoutSeconds;
        Server(String id, String name, String host, int port, String user, String keyUri, String hostFingerprint, int timeoutSeconds) { this.id = id; this.name = name; this.host = host; this.port = port; this.user = user; this.keyUri = keyUri; this.hostFingerprint = hostFingerprint; this.timeoutSeconds = timeoutSeconds; }
        Server withFingerprint(String pin) { return new Server(id, name, host, port, user, keyUri, pin, timeoutSeconds); }
        JSONObject toJson() { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", name); o.put("host", host); o.put("port", port); o.put("user", user); o.put("keyUri", keyUri); o.put("hostFingerprint", hostFingerprint); o.put("timeoutSeconds", timeoutSeconds); } catch (JSONException e) { throw new IllegalStateException(e); } return o; }
        static Server fromJson(JSONObject o) throws JSONException { return new Server(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("user"), o.getString("keyUri"), o.optString("hostFingerprint"), o.getInt("timeoutSeconds")); }
    }
    private static final class CommandButton {
        final String id, serverId, name, command;
        CommandButton(String id, String serverId, String name, String command) { this.id = id; this.serverId = serverId; this.name = name; this.command = command; }
        JSONObject toJson() { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("serverId", serverId); o.put("name", name); o.put("command", command); } catch (JSONException e) { throw new IllegalStateException(e); } return o; }
        static CommandButton fromJson(JSONObject o) throws JSONException { return new CommandButton(o.getString("id"), o.getString("serverId"), o.getString("name"), o.getString("command")); }
    }
}
