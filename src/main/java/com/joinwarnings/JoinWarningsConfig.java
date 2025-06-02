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
	@ConfigItem(
		keyName = "rejoinTimeout",
		name = "Rejoin timeout",
		description = "Delay before warning again on rejoin.",
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
		keyName = "highlightNames",
		name = "Highlight names",
		description = "Highlight flagged player names in chat messages and the member list.",
		position = 98
	)
	default boolean highlightNames()
	{
		return true;
	}
	@ConfigItem(
		keyName = "highlightColor",
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
		keyName = "ignoredPlayersString",
		name = "Ignored players",
		description = "Comma-separated list of player names to ignore (case-insensitive)."
	)
	default String ignoredPlayersString()
	{
		return "";
	}
}