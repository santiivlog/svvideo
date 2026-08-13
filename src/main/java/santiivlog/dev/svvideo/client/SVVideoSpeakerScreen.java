package santiivlog.dev.svvideo.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import santiivlog.dev.svvideo.network.SVVideoSpeakerActionPayload;
import santiivlog.dev.svvideo.network.SVVideoSpeakerStatePayload;
import santiivlog.dev.svvideo.util.SVVideoFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class SVVideoSpeakerScreen extends Screen {

    private static final int PANEL_W  = 640;
    private static final int PANEL_H  = 360;
    private static final int MARGIN   = 16;
    private static final int FILE_LIST_SIZE = 5;

    private static final Pattern YOUTUBE_ID_PATTERN =
            Pattern.compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final int C_OVERLAY   = 0xF2060C16;
    private static final int C_PANEL     = 0xFF0C1622;
    private static final int C_HEADER    = 0xFF091428;
    private static final int C_SECTION   = 0xFF101E30;
    private static final int C_BORDER    = 0xFF00C8FF;
    private static final int C_BORDER_DIM= 0xFF1C3050;
    private static final int C_TITLE     = 0xFF00D4FF;
    private static final int C_LABEL     = 0xFFCCDDEE;
    private static final int C_SUB       = 0xFF7A9AB8;
    private static final int C_HELPER    = 0xFF3A6080;
    private static final int C_ERROR     = 0xFFFF5555;

    private final BlockPos blockPos;
    private final SVVideoSpeakerStatePayload initialPayload;
    private String mode;
    private boolean paused;
    private String errorMessage = "";

    private TextFieldWidget sourceField;
    private TextFieldWidget volumeField;
    private TextFieldWidget minDistanceField;
    private TextFieldWidget maxDistanceField;
    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget qualityField;
    private ButtonWidget   modeButton;
    private ButtonWidget   pauseButton;
    private final List<ButtonWidget> fileButtons = new ArrayList<>();
    private List<String> availableFiles = List.of();

    public SVVideoSpeakerScreen(SVVideoSpeakerStatePayload payload) {
        super(Text.literal("SV Video Menu"));
        this.initialPayload = payload;
        this.blockPos       = BlockPos.fromLong(payload.blockPos());
        this.mode           = payload.mode();
        this.paused         = payload.paused();
    }

    public static void open(SVVideoSpeakerStatePayload payload) {
        MinecraftClient.getInstance().setScreen(new SVVideoSpeakerScreen(payload));
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, C_OVERLAY);
    }

    @Override
    protected void init() {
        int px   = this.width  / 2 - PANEL_W / 2;
        int py   = this.height / 2 - PANEL_H / 2;
        int left = px + MARGIN;
        int right= px + PANEL_W - MARGIN;

        int srcRowY = py + 44;
        modeButton = addDrawableChild(ButtonWidget.builder(
                Text.literal(labelForMode()), button -> {
                    mode = "url".equals(mode) ? "file" : "url";
                    button.setMessage(Text.literal(labelForMode()));
                    updateSourceSuggestion();
                    refreshFileButtons();
                }).dimensions(left, srcRowY, 110, 20).build());

        sourceField = new TextFieldWidget(textRenderer,
                left + 118, srcRowY,
                right - (left + 118), 20,
                Text.literal("source"));
        sourceField.setMaxLength(512);
        sourceField.setChangedListener(v -> { errorMessage = ""; refreshFileButtons(); });
        addDrawableChild(sourceField);
        updateSourceSuggestion();

        int fw  = 82;
        int gap = 12;
        int sfY = py + 100;
        volumeField      = numericField(left,              sfY, fw, "100");
        minDistanceField = numericField(left + (fw+gap),   sfY, fw, "5");
        maxDistanceField = numericField(left + (fw+gap)*2, sfY, fw, "20");
        widthField       = numericField(left + (fw+gap)*3, sfY, fw, "1");
        heightField      = numericField(left + (fw+gap)*4, sfY, fw, "1");
        qualityField     = numericField(left + (fw+gap)*5, sfY, fw, "100");

        int fileTopY = py + 182;
        for (int i = 0; i < FILE_LIST_SIZE; i++) {
            ButtonWidget btn = ButtonWidget.builder(Text.literal(""), button -> {
                String v = button.getMessage().getString();
                if (!v.isBlank()) { sourceField.setText(v); errorMessage = ""; }
            }).dimensions(left, fileTopY + i * 20, right - left, 18).build();
            fileButtons.add(addDrawableChild(btn));
        }

        int actionY = py + PANEL_H - 30;
        int bw = (right - left - 18) / 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("save"),
                button -> send("save"))
                .dimensions(left,              actionY, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("reload"),
                button -> send("reload"))
                .dimensions(left + bw + 6,     actionY, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("reload all"),
                button -> send("reload_all"))
                .dimensions(left + (bw+6)*2,   actionY, bw, 20).build());
        pauseButton = addDrawableChild(ButtonWidget.builder(
                Text.literal(paused ? "resume" : "pause"), button -> {
                    if (send("pause")) {
                        paused = !paused;
                        button.setMessage(Text.literal(paused ? "resume" : "pause"));
                    }
                }).dimensions(left + (bw+6)*3, actionY, bw, 20).build());

        applyPayload(initialPayload);
        loadAvailableFiles();
        refreshFileButtons();
    }

    private TextFieldWidget numericField(int x, int y, int w, String def) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 20, Text.literal(""));
        f.setMaxLength(3);
        f.setText(def);
        f.setChangedListener(t -> {
            if (!t.matches("\\d*")) f.setText(t.replaceAll("[^\\d]", ""));
            errorMessage = "";
        });
        addDrawableChild(f);
        return f;
    }

    public void applyPayload(SVVideoSpeakerStatePayload p) {
        mode   = p.mode();
        paused = p.paused();
        String sourceValue = "file".equals(p.mode()) ? SVVideoFiles.displayNameForSource(p.source()) : p.source();
        if (sourceField      != null) sourceField.setText(sourceValue);
        if (volumeField      != null) volumeField.setText(String.valueOf(p.volume()));
        if (minDistanceField != null) minDistanceField.setText(String.valueOf(p.minDistance()));
        if (maxDistanceField != null) maxDistanceField.setText(String.valueOf(p.maxDistance()));
        if (widthField       != null) widthField.setText(String.valueOf(p.displayWidth()));
        if (heightField      != null) heightField.setText(String.valueOf(p.displayHeight()));
        if (qualityField     != null) qualityField.setText(String.valueOf(p.renderQuality()));
        if (modeButton       != null) modeButton.setMessage(Text.literal(labelForMode()));
        if (pauseButton      != null) pauseButton.setMessage(Text.literal(paused ? "resume" : "pause"));
        updateSourceSuggestion();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int px   = this.width  / 2 - PANEL_W / 2;
        int py   = this.height / 2 - PANEL_H / 2;
        int left = px + MARGIN;
        int right= px + PANEL_W - MARGIN;

        context.fill(px, py, px + PANEL_W, py + PANEL_H, C_PANEL);
        border(context, px,   py,   PANEL_W,   PANEL_H,   0xFF0A1828);
        border(context, px+1, py+1, PANEL_W-2, PANEL_H-2, C_BORDER);

        context.fill(px+2, py+2, px+PANEL_W-2, py+26, C_HEADER);
        context.fill(px+2, py+26, px+PANEL_W-2, py+27, C_BORDER);
        context.drawTextWithShadow(textRenderer, Text.literal("SVVideo Speaker"),
                left, py + 10, C_TITLE);
        String badge = "◉ " + ("file".equals(mode) ? "FILE" : "URL");
        context.drawTextWithShadow(textRenderer, Text.literal(badge),
                right - textRenderer.getWidth(badge), py + 10, C_SUB);

        int s1 = py + 28;
        section(context, left, s1, right - left, 44);
        context.drawTextWithShadow(textRenderer, Text.literal("MEDIA SOURCE"),
                left + 4, s1 + 3, C_LABEL);
        if (!errorMessage.isBlank()) {
            context.drawTextWithShadow(textRenderer, Text.literal("⚠ " + errorMessage),
                    left + 4, py + 74, C_ERROR);
        }

        int s2 = py + 80;
        section(context, left, s2, right - left, 62);
        context.drawTextWithShadow(textRenderer, Text.literal("MEDIA SETTINGS"),
                left + 4, s2 + 3, C_LABEL);

        int fw = 82, gap = 12;
        drawSubLabel(context, "Volume",       left,              py + 88);
        drawSubLabel(context, "Min Dist",     left + (fw+gap),   py + 88);
        drawSubLabel(context, "Max Dist",     left + (fw+gap)*2, py + 88);
        drawSubLabel(context, "Width",        left + (fw+gap)*3, py + 88);
        drawSubLabel(context, "Height",       left + (fw+gap)*4, py + 88);
        drawSubLabel(context, "Quality %",    left + (fw+gap)*5, py + 88);

        int s3 = py + 148;
        int s3h = 32 + FILE_LIST_SIZE * 20 + 2;
        section(context, left, s3, right - left, s3h);
        context.drawTextWithShadow(textRenderer, Text.literal("AVAILABLE FILES"),
                left + 4, s3 + 3, C_LABEL);
        String hint = "file".equals(mode)
                ? "Click a file or type to filter — reads from /svvideo"
                : "Paste a direct media URL or a full YouTube link";
        context.drawTextWithShadow(textRenderer, Text.literal(hint),
                left + 4, s3 + 16, C_HELPER);

        int sepY = py + PANEL_H - 38;
        context.fill(px+2, sepY, px+PANEL_W-2, sepY+1, C_BORDER_DIM);

        super.render(context, mouseX, mouseY, delta);
    }

    private void section(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x+w, y+h, C_SECTION);
        border(ctx, x, y, w, h, C_BORDER_DIM);
    }

    private void border(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,     y,     x+w,   y+1,   color);
        ctx.fill(x,     y+h-1, x+w,   y+h,   color);
        ctx.fill(x,     y,     x+1,   y+h,   color);
        ctx.fill(x+w-1, y,     x+w,   y+h,   color);
    }

    private void drawSubLabel(DrawContext ctx, String text, int x, int y) {
        ctx.drawTextWithShadow(textRenderer, Text.literal(text), x, y, C_SUB);
    }

    private boolean send(String action) {
        if (!ClientPlayNetworking.canSend(SVVideoSpeakerActionPayload.ID)) {
            errorMessage = "El servidor no registro el canal de SVVideo.";
            return false;
        }

        String source = sourceField.getText().trim();
        if ("file".equals(mode)) {
            source = SVVideoFiles.displayNameForSource(source);
            sourceField.setText(source);
        }
        String normalizedSource = source;
        if (normalizedSource.isBlank()) {
            errorMessage = "La fuente no puede estar vacia."; return false;
        }
        if ("file".equals(mode) && availableFiles.stream().noneMatch(n -> n.equalsIgnoreCase(normalizedSource))) {
            errorMessage = "Ese archivo no existe en /svvideo."; return false;
        }
        if ("url".equals(mode) && looksLikeBrokenYoutubeUrl(normalizedSource)) {
            errorMessage = "La URL de YouTube no tiene video id valido."; return false;
        }
        int volume = parseInt(volumeField,      100, 0,   100, "Volumen invalido.");    if (volume < 0) return false;
        int min    = parseInt(minDistanceField,  5,  0,    64, "Distancia minima invalida."); if (min    < 0) return false;
        int max    = parseInt(maxDistanceField, 20,  1,   128, "Distancia maxima invalida."); if (max    < 0) return false;
        int displayWidth  = parseInt(widthField,   1,  1,  50, "Ancho invalido."); if (displayWidth  < 0) return false;
        int displayHeight = parseInt(heightField,  1,  1,  50, "Alto invalido.");  if (displayHeight < 0) return false;
        int renderQuality = parseInt(qualityField, 100, 25, 100, "Calidad invalida."); if (renderQuality < 0) return false;
        max = Math.max(min + 1, max);

        ClientPlayNetworking.send(new SVVideoSpeakerActionPayload(
                blockPos.asLong(), action, mode, normalizedSource, volume, min, max, displayWidth, displayHeight, renderQuality));
        errorMessage = "";
        return true;
    }

    private int parseInt(TextFieldWidget field, int fallback, int min, int max, String error) {
        String text = field.getText().trim();
        if (text.isBlank()) { field.setText(String.valueOf(fallback)); return fallback; }
        try {
            int v = Integer.parseInt(text);
            if (v < min || v > max) { errorMessage = error; return -1; }
            return v;
        } catch (NumberFormatException e) {
            errorMessage = error; return -1;
        }
    }

    private void loadAvailableFiles() {
        try {
            SVVideoFiles.createFolder();
            Path folder = SVVideoFiles.getFolder();
            if (!Files.exists(folder)) { availableFiles = List.of(); return; }
            availableFiles = Files.list(folder)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String l = n.toLowerCase();
                        return l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".mov")
                            || l.endsWith(".mkv")  || l.endsWith(".gif")  || l.endsWith(".webp")
                            || l.endsWith(".ogg")  || l.endsWith(".mp3")  || l.endsWith(".wav")
                            || l.endsWith(".aac")  || l.endsWith(".m4a")  || l.endsWith(".flac")
                            || l.endsWith(".opus");
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Exception ignored) { availableFiles = List.of(); }
    }

    private void refreshFileButtons() {
        String filter  = sourceField == null ? "" : sourceField.getText().trim().toLowerCase();
        boolean isFile = "file".equals(mode);
        int shown = 0;

        for (ButtonWidget b : fileButtons) { b.visible = false; b.active = false; b.setMessage(Text.literal("")); }
        if (!isFile) return;

        for (String file : availableFiles) {
            if (!filter.isBlank() && !file.toLowerCase().contains(filter)) continue;
            if (shown >= fileButtons.size()) break;
            ButtonWidget b = fileButtons.get(shown++);
            b.visible = true; b.active = true;
            b.setMessage(Text.literal(file));
        }
    }

    private void updateSourceSuggestion() {
        if (sourceField == null) return;
        sourceField.setPlaceholder(Text.literal("file".equals(mode)
                ? "archivo.mp4"
                : "https://www.youtube.com/watch?v=..."));
    }

    private String labelForMode() {
        return "Modo: " + ("file".equals(mode) ? "file" : "url");
    }

    private boolean looksLikeBrokenYoutubeUrl(String source) {
        String l = source.toLowerCase();
        if (!l.contains("youtube.com/watch") && !l.contains("youtu.be/")) return false;
        return !YOUTUBE_ID_PATTERN.matcher(source).find();
    }
}

