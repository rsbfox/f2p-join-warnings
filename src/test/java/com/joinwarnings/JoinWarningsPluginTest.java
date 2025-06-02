package com.joinwarnings;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class JoinWarningsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(JoinWarningsPlugin.class);
		RuneLite.main(args);
	}
}