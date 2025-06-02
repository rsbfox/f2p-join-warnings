package com.joinwarnings;

import com.google.common.base.MoreObjects;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.FriendsChatRank;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ChatColorConfig;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldType;
import org.apache.commons.lang3.StringUtils;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.client.ui.JagexColors.CHAT_FC_NAME_OPAQUE_BACKGROUND;
import static net.runelite.client.ui.JagexColors.CHAT_FC_NAME_TRANSPARENT_BACKGROUND;

@Slf4j
@PluginDescriptor(
	name = "F2P Join Warnings"
)
public class JoinWarningsPlugin extends Plugin
{
	private final Map<String, Integer> playerLeaveTicks = new HashMap<>();
	private final Set<String> ignoredPlayers = new HashSet<>();
	private final Set<String> flaggedPlayers = new HashSet<>();

	@Inject
	private Client client;

	@Inject
	private JoinWarningsConfig config;

	@Inject
	private ChatColorConfig chatColorConfig;

	@Inject
	private ClientThread clientThread;

	@Inject
	private WorldService worldService;

	@Inject
	private Notifier notifier;

	private int joinedTick;

	@Provides
	JoinWarningsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(JoinWarningsConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		playerLeaveTicks.clear();
		flaggedPlayers.clear();
		updateIgnoredPlayers();
		updateFlaggedPlayers();
	}

	@Override
	protected void shutDown() throws Exception
	{
		playerLeaveTicks.clear();
		flaggedPlayers.clear();
		clientThread.invoke(this::applyMemberListHighlight);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals("joinwarnings"))
		{
			return;
		}

		updateIgnoredPlayers();
		flaggedPlayers.removeAll(ignoredPlayers);
		updateFlaggedPlayers();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.FRIENDS_CHAT_CHANNEL_REBUILD)
		{
			return;
		}

		if (config.highlightNames())
		{
			applyMemberListHighlight();
		}
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		playerLeaveTicks.clear();
		flaggedPlayers.clear();

		if (!event.isJoined())
		{
			return;
		}

		joinedTick = client.getTickCount();
		updateFlaggedPlayers();
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		// ignore events from joining chat
		if (joinedTick == client.getTickCount())
		{
			return;
		}

		final FriendsChatMember member = event.getMember();

		if (!shouldFlag(member))
		{
			return;
		}

		flaggedPlayers.add(Text.toJagexName(member.getName()).toLowerCase());
		applyMemberListHighlight();

		final Integer lastLeaveTick = playerLeaveTicks.get(member.getName());
		if (lastLeaveTick != null && client.getTickCount() - lastLeaveTick <= config.getTimeout() * 100)
		{
			return;
		}

		if (config.showFriendsChatWarning())
		{
			final FriendsChatManager friendsChatManager = client.getFriendsChatManager();
			if (friendsChatManager == null)
			{
				return;
			}

			final Color infoColor, channelColor;
			if (client.isResized() && client.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) == 1)
			{
				infoColor = MoreObjects.firstNonNull(chatColorConfig.transparentFriendsChatInfo(), Color.WHITE);
				channelColor = MoreObjects.firstNonNull(chatColorConfig.transparentFriendsChatChannelName(), CHAT_FC_NAME_TRANSPARENT_BACKGROUND);
			}
			else
			{
				infoColor = MoreObjects.firstNonNull(chatColorConfig.opaqueFriendsChatInfo(), Color.BLACK);
				channelColor = MoreObjects.firstNonNull(chatColorConfig.opaqueFriendsChatChannelName(), CHAT_FC_NAME_OPAQUE_BACKGROUND);
			}

			ChatMessageBuilder message = new ChatMessageBuilder()
				.append(infoColor, "[")
				.append(channelColor, friendsChatManager.getName())
				.append(infoColor, "] ")
				.append(infoColor, member.getName() + " has joined. ")
				.append(config.hightlightColor(), "(Unranked F2P)");

			client.addChatMessage(ChatMessageType.FRIENDSCHATNOTIFICATION, "", message.build(), "");
		}

		if (config.sendFriendsChatNotification().isEnabled())
		{
			notifier.notify(config.sendFriendsChatNotification(), member.getName() + " has joined. (Unranked F2P)");
		}

	}

	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		final FriendsChatMember member = event.getMember();

		flaggedPlayers.remove(Text.toJagexName(member.getName()).toLowerCase());
		playerLeaveTicks.put(member.getName(), client.getTickCount());
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (!config.highlightNames())
		{
			return;
		}

		if (chatMessage.getType() != ChatMessageType.FRIENDSCHAT)
		{
			return;
		}

		final MessageNode messageNode = chatMessage.getMessageNode();
		final String name = Text.toJagexName(Text.removeTags(chatMessage.getName())).toLowerCase();

		if (flaggedPlayers.contains(name))
		{
			messageNode.setName(ColorUtil.wrapWithColorTag(chatMessage.getName(), config.hightlightColor()));
			messageNode.setRuneLiteFormatMessage(messageNode.getValue());
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.highlightNames())
		{
			return;
		}

		if (event.getActionParam1() == InterfaceID.ChatchannelCurrent.LIST)
		{
			final String name = Text.toJagexName(Text.removeTags(event.getTarget())).toLowerCase();

			if (flaggedPlayers.contains(name))
			{
				event.getMenuEntry().setTarget(ColorUtil.wrapWithColorTag(Text.removeTags(event.getTarget()), config.hightlightColor()));
			}
		}
	}

	private void updateIgnoredPlayers()
	{
		ignoredPlayers.clear();
		ignoredPlayers.addAll(
			Arrays.stream(config.ignoredPlayersString().split(","))
				.map(Text::toJagexName)
				.map(String::toLowerCase)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toSet())
		);
	}

	private void updateFlaggedPlayers()
	{
		clientThread.invokeLater(() ->
		{
			final FriendsChatManager friendsChatManager = client.getFriendsChatManager();
			if (friendsChatManager == null)
			{
				log.debug("FriendsChatManager is null");
				return true; // No longer in chat
			}

			final FriendsChatMember[] members = friendsChatManager.getMembers();
			if (members.length == 0)
			{
				log.debug("FriendsChatManager not populated yet");
				return false; // Try again next tick
			}

			for (FriendsChatMember member : members)
			{
				if (shouldFlag(member))
				{
					flaggedPlayers.add(Text.toJagexName(member.getName()).toLowerCase());
				}
			}

			applyMemberListHighlight();

			return true;
		});
	}

	private void applyMemberListHighlight()
	{
		final boolean enabled = config.highlightNames();

		final Widget chatList = client.getWidget(InterfaceID.ChatchannelCurrent.LIST);
		if (chatList == null || chatList.getChildren() == null)
		{
			return;
		}

		final Widget[] children = chatList.getChildren();
		for (int i = 0; i < children.length; i += 3)
		{
			final Widget listWidget = children[i];

			if (!enabled)
			{
				listWidget.setTextColor(Color.WHITE.getRGB());
				continue;
			}

			final String name = Text.toJagexName(StringUtils.defaultString(listWidget.getText())).toLowerCase();
			listWidget.setTextColor(flaggedPlayers.contains(name) ? config.hightlightColor().getRGB() : Color.WHITE.getRGB());
		}
	}

	private boolean shouldFlag(FriendsChatMember member)
	{
		if (!member.getRank().equals(FriendsChatRank.UNRANKED))
		{
			return false;
		}

		if (ignoredPlayers.contains(Text.toJagexName(member.getName()).toLowerCase()))
		{
			return false;
		}

		World world = worldService.getWorlds().findWorld(member.getWorld());
		return world != null && !world.getTypes().contains(WorldType.MEMBERS);
	}
}