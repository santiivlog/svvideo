package santiivlog.dev.svvideo.client;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class SVVideoSlider extends SliderWidget {
    private final int min;
    private final int max;
    private final IntConsumer onChange;
    private final IntFunction<String> label;

    public SVVideoSlider(int x, int y, int width, int height, int min, int max, int value,
                         IntFunction<String> label, IntConsumer onChange) {
        super(x, y, width, height, Text.literal(""), normalize(value, min, max));
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.label = label;
        updateMessage();
    }

    public int intValue() {
        return min + (int) Math.round(value * (max - min));
    }

    public void setIntValue(int newValue) {
        this.value = normalize(newValue, min, max);
        this.onChange.accept(intValue());
        this.updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal(label.apply(intValue())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(intValue());
    }

    private static double normalize(int value, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return (double) (value - min) / (double) (max - min);
    }
}

