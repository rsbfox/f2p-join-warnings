package com.joinwarnings;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Units;

@ConfigGroup("joinwarnings")
public interface JoinWarningsConfig extends Config
{
	String GROUP = "joinwarnings";

	String HIGHLIGHT_NAMES_KEY = "highlightNames";
	String IGNORED_PLAYERS_KEY = "ignoredPlayersString";
	String HIGHLIGHT_COLOR_KEY = "highlightColor";

	@ConfigItem(
		keyName = "rejoinTimeout",
		name = "Rejoin timeout",
		description = "Time before warning again on rejoin. 0 to always warn.",
		position = 0
	)
	@Units(Units.MINUTES)
	default int getTimeout()
	{
		return 5;
	}

	@ConfigSection(
		name = "Friends chat",
		description = "Configuration for friends chat.",
		position = 1
	)
	String friendsChatSection = "friendsChat";

	@ConfigItem(
		keyName = "friendsChatWarning",
		name = "Warn on join",
		description = "Display a warning when an unranked F2P player joins.",
		position = 0,
		section = friendsChatSection
	)
	default boolean showFriendsChatWarning()
	{
		return true;
	}

	@ConfigItem(
		keyName = "friendsChatNotification",
		name = "Notify on join",
		description = "Send a notification when an unranked F2P player joins.",
		position = 1,
		section = friendsChatSection
	)
	default Notification sendFriendsChatNotification()
	{
		return Notification.OFF;
	}

	@ConfigItem(
		keyName = HIGHLIGHT_NAMES_KEY,
		name = "Highlight names",
		description = "Highlight flagged player names in chat messages and the member list.",
		position = 98
	)
	default boolean highlightNames()
	{
		return true;
	}
	@ConfigItem(
		keyName = HIGHLIGHT_COLOR_KEY,
		name = "Highlight color",
		description = "Set the color of the warning message and name highlights.",
		position = 99
	)
	default Color hightlightColor()
	{
		return Color.RED;
	}

	@ConfigItem(
		position = 100,
		keyName = IGNORED_PLAYERS_KEY,
		name = "Ignored players",
		description = "Players who are never warned about or highlighted. Comma-separated, case-insensitive."
	)
	default String ignoredPlayersString()
	{
		return "";
	}
}