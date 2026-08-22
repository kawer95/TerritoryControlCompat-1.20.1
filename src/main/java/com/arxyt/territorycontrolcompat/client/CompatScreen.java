package com.arxyt.territorycontrolcompat.client;

import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.arxyt.territorycontrolcompat.network.CompatNetwork;
import com.arxyt.territorycontrolcompat.network.SaveCompatPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CompatScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 520;
    private static final int NAV_WIDTH = 142;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private CompatSavedData.Config config;
    private Section selected = Section.CAERULA;
    private int left;
    private int top;
    private int panelWidth;
    private int footerY;

    public CompatScreen(Screen parent, CompatSavedData.Config config) {
        super(Component.literal("模组适配"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(360, width - 28));
        left = (width - panelWidth) / 2;
        top = 30;
        footerY = height - 34;

        int navY = top + 28;
        for (Section section : Section.values()) {
            addRenderableWidget(Button.builder(Component.literal(section.title), button -> {
                        selected = section;
                        rebuild();
                    })
                    .bounds(left + 8, navY, NAV_WIDTH - 16, 20)
                    .build());
            navY += ROW_HEIGHT;
        }

        addSettings();
        addRenderableWidget(Button.builder(Component.translatable("screen.territorycontrol.save"), button -> {
                    CompatNetwork.CHANNEL.sendToServer(new SaveCompatPacket(config));
                })
                .bounds(left + 8, footerY, 78, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.territorycontrol.close"), button -> onClose())
                .bounds(left + 92, footerY, 78, 20)
                .build());
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void addSettings() {
        int contentX = left + NAV_WIDTH + 14;
        int contentWidth = panelWidth - NAV_WIDTH - 22;
        int y = top + 42;
        switch (selected) {
            case CAERULA -> {
                addToggle(contentX, y, contentWidth, "平衡育生池密度", config::balancedOvaryDensity,
                        value -> config = new CompatSavedData.Config(value, config.restrictCaerula(), config.tideRecession(), config.restrictEyes(), config.eyesCollapse(), config.restrictPhayriosis(), config.phayriosisCure()));
                addToggle(contentX, y + ROW_HEIGHT, contentWidth, "只能在占领区扩散", config::restrictCaerula,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), value, config.tideRecession(), config.restrictEyes(), config.eyesCollapse(), config.restrictPhayriosis(), config.phayriosisCure()));
                addToggle(contentX, y + ROW_HEIGHT * 2, contentWidth, "退潮模式", config::tideRecession,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), config.restrictCaerula(), value, config.restrictEyes(), config.eyesCollapse(), config.restrictPhayriosis(), config.phayriosisCure()));
            }
            case EYES -> {
                addToggle(contentX, y, contentWidth, "只能在占领区扩散", config::restrictEyes,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), config.restrictCaerula(), config.tideRecession(), value, config.eyesCollapse(), config.restrictPhayriosis(), config.phayriosisCure()));
                addToggle(contentX, y + ROW_HEIGHT, contentWidth, "崩塌模式", config::eyesCollapse,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), config.restrictCaerula(), config.tideRecession(), config.restrictEyes(), value, config.restrictPhayriosis(), config.phayriosisCure()));
            }
            case PHAYRIOSIS -> {
                addToggle(contentX, y, contentWidth, "只能在占领区扩散", config::restrictPhayriosis,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), config.restrictCaerula(), config.tideRecession(), config.restrictEyes(), config.eyesCollapse(), value, config.phayriosisCure()));
                addToggle(contentX, y + ROW_HEIGHT, contentWidth, "解药模式", config::phayriosisCure,
                        value -> config = new CompatSavedData.Config(config.balancedOvaryDensity(), config.restrictCaerula(), config.tideRecession(), config.restrictEyes(), config.eyesCollapse(), config.restrictPhayriosis(), value));
            }
            case SPORE -> {
                addToggle(contentX, y, contentWidth, "菌囊仅能在实控区生成", config::restrictSporeMounds,
                        value -> config = config.withRestrictSporeMounds(value));
                addToggle(contentX, y + ROW_HEIGHT, contentWidth, "哨戒体仅能在实控区生成", config::restrictSporeVigils,
                        value -> config = config.withRestrictSporeVigils(value));
                addToggle(contentX, y + ROW_HEIGHT * 2, contentWidth, "菌染刷怪笼结构仅限实控区", config::restrictSporeSpawnerStructures,
                        value -> config = config.withRestrictSporeSpawnerStructures(value));
                addToggle(contentX, y + ROW_HEIGHT * 3, contentWidth, "失去实控权时还原侵蚀", config::restoreSporeOnLoss,
                        value -> config = config.withRestoreSporeOnLoss(value));
                addToggle(contentX, y + ROW_HEIGHT * 4, contentWidth, "侵蚀只能向实控区扩散", config::restrictSporeInfectionSpread,
                        value -> config = config.withRestrictSporeInfectionSpread(value));
            }
        }
    }

    private void addToggle(int x, int y, int buttonWidth, String label, BooleanSupplier value, Consumer<Boolean> setter) {
        boolean enabled = value.getAsBoolean();
        addRenderableWidget(Button.builder(Component.literal(label + "：" + (enabled ? "开" : "关")), button -> {
                    setter.accept(!enabled);
                    rebuild();
                })
                .bounds(x, y, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int bottom = footerY + 28;
        graphics.fill(left, top, left + panelWidth, bottom, 0xD8202020);
        graphics.fill(left, top, left + panelWidth, top + 24, 0xE0383838);
        graphics.fill(left, top + 24, left + NAV_WIDTH, footerY - 6, 0xA0181818);
        graphics.fill(left + NAV_WIDTH, top + 24, left + NAV_WIDTH + 1, footerY - 6, 0xFF555555);
        graphics.fill(left, footerY - 6, left + panelWidth, footerY - 5, 0xFF555555);
        graphics.drawString(font, title, left + 8, top + 8, 0xFFFFFF, false);
        graphics.drawString(font, Component.literal(selected.title), left + NAV_WIDTH + 14, top + 29, 0xFFFFFF, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private enum Section {
        CAERULA("方块与深蓝之树"),
        EYES("眼魔"),
        PHAYRIOSIS("法耶病"),
        SPORE("真菌感染：孢子");

        private final String title;

        Section(String title) {
            this.title = title;
        }
    }
}
