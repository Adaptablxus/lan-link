package com.lanlink.client.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Screen.addRenderableWidget} (protected, inherited by every screen but
 * declared on {@code Screen} itself) so mixins in other classes can add widgets.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {
	@Invoker("addRenderableWidget")
	GuiEventListener lanlink$invokeAddRenderableWidget(GuiEventListener widget);
}
