package com.amongus;

public enum Role {
	CREWMATE("Crewmate", "#7CFC00", false, "Finish your tasks and find the impostors!"),
	ENGINEER("Engineer", "#FFA500", false, "Crewmate. Right-click an iron-trapdoor vent: you can stay in the vents for a few seconds, then there is a cooldown."),
	SCIENTIST("Scientist", "#00FFFF", false, "Crewmate. Use /among vitals to see who is alive or dead (limited uses)."),
	IMPOSTOR("Impostor", "#FF3030", true, "Kill with the red sword, vent, and sabotage (/among sabotage lights|reactor)."),
	SHAPESHIFTER("Shapeshifter", "#FF3030", true, "Impostor. Use /among shift <color> to look like someone else for a while.");

	public final String display;
	public final String color;
	public final boolean impostor;
	public final String desc;

	Role(String display, String color, boolean impostor, String desc) {
		this.display = display;
		this.color = color;
		this.impostor = impostor;
		this.desc = desc;
	}
}
