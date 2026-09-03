package com.ming.modernwar_kd;

import com.google.gson.annotations.Expose;

public class PlayerStats {

    @Expose private int kills;
    @Expose private int assists;
    @Expose private int deaths;
    @Expose private int wins;
    @Expose private int losses;
    @Expose private int heads;

    public PlayerStats() {}

    public PlayerStats(int kills, int assists, int deaths, int wins, int losses, int heads) {
        this.kills = kills;
        this.assists = assists;
        this.deaths = deaths;
        this.wins = wins;
        this.losses = losses;
        this.heads = heads;
    }

    // --- kills ---
    public int getKills() { return kills; }
    public void addKill() { this.kills++; }
    public void addKills(int n) { this.kills += n; }
    public void setKills(int kills) { this.kills = kills; }

    // --- assists ---
    public int getAssists() { return assists; }
    public void addAssist() { this.assists++; }
    public void addAssists(int n) { this.assists += n; }
    public void setAssists(int assists) { this.assists = assists; }

    // --- deaths ---
    public int getDeaths() { return deaths; }
    public void addDeath() { this.deaths++; }
    public void addDeaths(int n) { this.deaths += n; }
    public void setDeaths(int deaths) { this.deaths = deaths; }

    // --- wins ---
    public int getWins() { return wins; }
    public void addWin() { this.wins++; }
    public void addWins(int n) { this.wins += n; }
    public void setWins(int wins) { this.wins = wins; }

    // --- losses ---
    public int getLosses() { return losses; }
    public void addLoss() { this.losses++; }
    public void addLosses(int n) { this.losses += n; }
    public void setLosses(int losses) { this.losses = losses; }

    // --- heads ---
    public int getHeads() { return heads; }
    public void addHead() { this.heads++; }
    public void addHeads(int n) { this.heads += n; }
    public void setHeads(int heads) { this.heads = heads; }

    // --- derived ---

    /** matches = wins + losses */
    public int getMatches() { return wins + losses; }

    /**
     * API KD formula: kills / matches, 2 decimal places.
     * If matches == 0, returns 0.0.
     */
    public double getApiKD() {
        int matches = getMatches();
        if (matches == 0) return 0.0;
        return Math.round((double) kills / matches * 100.0) / 100.0;
    }

    /** Legacy in-game KD: (kills + assists) / deaths */
    public double getKD() {
        if (deaths == 0) return kills + assists;
        return (double) (kills + assists) / deaths;
    }

    @Override
    public String toString() {
        return String.format("K:%d A:%d D:%d W:%d L:%d H:%d Matches:%d KD:%.2f",
                kills, assists, deaths, wins, losses, heads, getMatches(), getApiKD());
    }
}
