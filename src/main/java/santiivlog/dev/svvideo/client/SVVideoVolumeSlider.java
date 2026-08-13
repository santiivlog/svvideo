package santiivlog.dev.svvideo.client;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class SVVideoVolumeSlider extends SliderWidget {

    public SVVideoVolumeSlider(int x, int y, int width, int height) {
        super(
                x,
                y,
                width,
                height,
                Text.literal("SVVideo: " + SVVideoSettings.getLocalVolume() + "%"),
                SVVideoSettings.getLocalVolume() / 100.0
        );
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal("SVVideo: " + SVVideoSettings.getLocalVolume() + "%"));
    }

    @Override
    protected void applyValue() {
        SVVideoSettings.setLocalVolume((int) Math.round(this.value * 100));
        updateMessage();
    }
}

