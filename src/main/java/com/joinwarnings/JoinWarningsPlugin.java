package com.joinwarnings;

import com.google.common.base.MoreObjects;
import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.FriendsChatRank;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptCallbackEvent;
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
import net.runelite.client.events.WorldsFetch;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
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
@PluginDescriptor(name = "F2P Join Warnings")
public class JoinWarningsPlugin extends Plugin
{
	private static final Pattern COL_TAG = Pattern.compile("</?col(=[^>]*)?>");

	private final Map<String, Instant> lastLeft = new HashMap<>();
	private final Set<String> presentMembers = new HashSet<>();
	private final Set<String> ignoredPlayers = new HashSet<>();
	private final Set<String> flaggedPlayers = new HashSet<>();

	private volatile Set<Integer> f2pWorlds = Collections.emptySet();

	@Inject
	private Client client;

	@Inject
	private JoinWarningsConfig config;

	@Inject
	private ChatColorConfig chatColorConfig;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

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
		updateIgnoredPlayers();
		executor.execute(() -> {
			final WorldResult result = worldService.getWorlds();
			if (result != null)
			{
				updateF2pWorlds(result);
			}
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		lastLeft.clear();
		presentMembers.clear();
		ignoredPlayers.clear();
		flaggedPlayers.clear();
		if (config.highlightNames())
		{
			clientThread.invokeLater(() -> {
				resetMemberListHighlight();
				client.refreshChat();
			});
		}
	}

	@Subscribe
	public void onWorldsFetch(WorldsFetch event)
	{
		if (!f2pWorlds.isEmpty())
		{
			return;
		}

		updateF2pWorlds(event.getWorldResult());
	}

	private void updateF2pWorlds(WorldResult result)
	{
		final Set<Integer> f2pWorlds = new HashSet<>();
		for (World world : result.getWorlds())
		{
			if (!world.getTypes().contains(WorldType.MEMBERS))
			{
				f2pWorlds.add(world.getId());
			}
		}
		this.f2pWorlds = f2pWorlds;
		updateFlaggedPlayers();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals(JoinWarningsConfig.GROUP))
		{
			return;
		}

		switch (configChanged.getKey())
		{
			case JoinWarningsConfig.HIGHLIGHT_NAMES_KEY:
			case JoinWarningsConfig.HIGHLIGHT_COLOR_KEY:
				clientThread.invokeLater(() -> {
					if (config.highlightNames())
					{
						applyMemberListHighlight();
					}
					else
					{
						resetMemberListHighlight();
					}
					client.refreshChat();
				});
				break;
			case JoinWarningsConfig.IGNORED_PLAYERS_KEY:
				updateIgnoredPlayers();
				flaggedPlayers.removeAll(ignoredPlayers);
				updateFlaggedPlayers();
				break;
		}
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

	// run after chat name colors plugin
	@Subscribe(priority = -1)
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!config.highlightNames() || flaggedPlayers.isEmpty() || !"chatMessageBuilding".equals(event.getEventName()))
		{
			return;
		}

		// based on https://github.com/runelite/runelite/blob/e98399f931c60bfa8e42f924da850f508ec8e00d/runelite-client/src/main/java/net/runelite/client/chat/ChatMessageManager.java#L102
		final int[] intStack = client.getIntStack();
		final Object[] objectStack = client.getObjectStack();
		final int isize = client.getIntStackSize();
		final int osize = client.getObjectStackSize();
		final int uid = intStack[isize - 1];

		final MessageNode messageNode = client.getMessages().get(uid);
		if (messageNode == null || messageNode.getType() != ChatMessageType.FRIENDSCHAT)
		{
			return;
		}

		final String username = (String) objectStack[osize - 3];
		if (!flaggedPlayers.contains(Text.toJagexName(Text.removeTags(username)).toLowerCase()))
		{
			return;
		}

		objectStack[osize - 3] = ColorUtil.wrapWithColorTag(removeColTags(username), config.hightlightColor());
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		if (!event.isJoined())
		{
			return;
		}

		flaggedPlayers.clear();
		presentMembers.clear();
		joinedTick = client.getTickCount();
		updateFlaggedPlayers();
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		final FriendsChatMember member = event.getMember();
		// workaround: ignore joins for players already present
		if (!presentMembers.add(member.getName()))
		{
			return;
		}

		// ignore events from joining chat
		if (joinedTick == client.getTickCount())
		{
			return;
		}

		if (!shouldFlag(member))
		{
			// remove players that hopped from f2p to members
			flaggedPlayers.remove(Text.toJagexName(member.getName()).toLowerCase());
			return;
		}

		flaggedPlayers.add(Text.toJagexName(member.getName()).toLowerCase());

		if (config.highlightNames())
		{
			applyMemberListHighlight();
		}

		final Instant left = lastLeft.get(member.getName());
		if (left != null && Duration.between(left, Instant.now()).toMinutes() < config.getTimeout())
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

		presentMembers.remove(member.getName());
		lastLeft.put(member.getName(), Instant.now());
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

			if (config.highlightNames())
			{
				applyMemberListHighlight();
				client.refreshChat();
			}

			return true;
		});
	}

	private void applyMemberListHighlight()
	{
		colorMemberList(name -> flaggedPlayers.contains(name) ? config.hightlightColor() : Color.WHITE);
	}

	private void resetMemberListHighlight()
	{
		colorMemberList(name -> Color.WHITE);
	}

	private void colorMemberList(Function<String, Color> colorForName)
	{
		final Widget chatList = client.getWidget(InterfaceID.ChatchannelCurrent.LIST);
		if (chatList == null || chatList.getChildren() == null)
		{
			return;
		}

		final Widget[] children = chatList.getChildren();
		for (int i = 0; i < children.length; i += 3)
		{
			if (children[i] == null)
			{
				continue;
			}

			final String name = Text.toJagexName(StringUtils.defaultString(children[i].getText())).toLowerCase();
			children[i].setTextColor(colorForName.apply(name).getRGB());
		}
	}

	private boolean shouldFlag(FriendsChatMember member)
	{
		if (member.getRank() != FriendsChatRank.UNRANKED)
		{
			return false;
		}

		if (ignoredPlayers.contains(Text.toJagexName(member.getName()).toLowerCase()))
		{
			return false;
		}

		return f2pWorlds.contains(member.getWorld());
	}

	private static String removeColTags(String str)
	{
		return COL_TAG.matcher(str).replaceAll("");
	}
}