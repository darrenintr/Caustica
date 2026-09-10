package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaMod;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Two-step confirm gate for the probe flight-recorder toggle.
 *
 * <p>Flipping the toggle ON does not enable recording immediately: the value-update
 * listener reverts the widget to OFF, opens a {@link ConfirmScreen} warning that
 * recording writes a per-frame CSV plus GPU moment passes (extra disk + a small
 * frame-time cost), and only enables the setting when the player picks
 * "I know what I'm doing". Picking "Turn off" (or closing the dialog) leaves
 * recording off. Flipping the toggle OFF always applies immediately, no dialog.
 *
 * <p>Why the revert-then-confirm dance: a CycleButton applies the new value and
 * repaints itself before the listener runs, so the only way to keep the widget
 * truthful while the dialog is open is to {@code set(false)} back through the
 * same instance. Both programmatic drives (the revert and the confirm-apply)
 * are guarded by flags so they never reopen the dialog.
 */
public final class ProbeConfirmGate {
    private ProbeConfirmGate() {
    }

    private static final class GateListener implements OptionInstance.ValueUpdateListener<Boolean> {
        private final BooleanSetting setting;
        // True while WE drive the widget (revert-to-OFF, confirm-apply):
        // those set() calls must not reopen the dialog.
        private boolean changing;
        // True only between the revert and the confirm callback: the
        // confirm-apply set(true) must not reopen the dialog either.
        private boolean confirming;

        private GateListener(BooleanSetting setting) {
            this.setting = setting;
        }

        @Override
        public void valueChanged(Boolean value) {
            if (changing || confirming) {
                return;
            }
            if (!Boolean.TRUE.equals(value)) {
                setting.set(false);
                return;
            }
            OptionInstance<Boolean> self = ProbeToggleHolder.current();
            if (self == null) {
                setting.set(false);
                return;
            }
            // Revert the widget to OFF while the dialog is open, then ask.
            changing = true;
            try {
                self.set(false);
            } finally {
                changing = false;
            }
            confirming = true;
            openConfirm(this, self, setting);
        }
    }

    public static OptionInstance.ValueUpdateListener<Boolean> onProbeToggle(BooleanSetting setting) {
        return new GateListener(setting);
    }

    private static void openConfirm(GateListener gate, OptionInstance<Boolean> self, BooleanSetting setting) {
        Minecraft mc = Minecraft.getInstance();
        Screen back = mc.gui.screen();
        BooleanConsumer callback = confirmed -> {
            try {
                if (confirmed) {
                    gate.changing = true;
                    try {
                        setting.set(true);
                        self.set(true);
                    } finally {
                        gate.changing = false;
                    }
                    CausticaConfig.save();
                    CausticaMod.LOGGER.info("RtProbe: recording enabled from options screen");
                }
            } finally {
                gate.confirming = false;
                mc.gui.setScreen(back);
            }
        };
        Component title = Component.translatable("caustica.options.rt.probeRecord.confirm.title");
        Component message = Component.translatable("caustica.options.rt.probeRecord.confirm.message");
        Component yes = Component.translatable("caustica.options.rt.probeRecord.confirm.yes");
        // "Turn off" doubles as the dialog's negative action: recording stays off.
        Component no = CommonComponents.OPTION_OFF
                .copy()
                .append(Component.translatable("caustica.options.rt.probeRecord.confirm.offSuffix"));
        mc.gui.setScreen(new ConfirmScreen(callback, title, message, yes, no));
    }

    /**
     * Holds the live probe toggle instance so the confirm callback can repaint it
     * after the player confirms. Set when the options screen builds the widget,
     * cleared when the screen closes.
     */
    public static final class ProbeToggleHolder {
        private static OptionInstance<Boolean> current;

        private ProbeToggleHolder() {
        }

        static OptionInstance<Boolean> current() {
            return current;
        }

        public static void set(OptionInstance<Boolean> option) {
            current = option;
        }

        public static void clear() {
            current = null;
        }
    }
}
