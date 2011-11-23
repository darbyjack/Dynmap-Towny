package org.dynmap.towny;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyUniverse;
import com.palmergames.bukkit.towny.object.TownyWorld;

public class DynmapTownyPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-Towny] ";
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname% (%nation%)</span><br /> Mayor <span style=\"font-weight:bold;\">%playerowners%</span><br /> Associates <span style=\"font-weight:bold;\">%playermanagers%</span><br/>Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    Towny towny;
    TownyUniverse tuniv;
    int townblocksize;
    
    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop;
    
    private class AreaStyle {
        String strokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String homemarker;
        String capitalmarker;
        MarkerIcon homeicon;
        MarkerIcon capitalicon;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            homemarker = cfg.getString(path+".homeicon", def.homemarker);
            if(homemarker != null) {
                homeicon = markerapi.getMarkerIcon(homemarker);
                if(homeicon == null) {
                    severe("Invalid homeicon: " + homemarker);
                    homeicon = markerapi.getMarkerIcon("blueicon");
                }
            }
            capitalmarker = cfg.getString(path+".capitalicon", def.capitalmarker);
            if(capitalmarker != null) {
                capitalicon = markerapi.getMarkerIcon(capitalmarker);
                if(capitalicon == null) {
                    severe("Invalid capitalicon: " + capitalmarker);
                    capitalicon = markerapi.getMarkerIcon("king");
                }
            }
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
            homemarker = cfg.getString(path+".homeicon", null);
            if(homemarker != null) {
                homeicon = markerapi.getMarkerIcon(homemarker);
                if(homeicon == null) {
                    severe("Invalid homeicon: " + homemarker);
                    homeicon = markerapi.getMarkerIcon("blueicon");
                }
            }
            capitalmarker = cfg.getString(path+".capitalicon", null);
            if(capitalmarker != null) {
                capitalicon = markerapi.getMarkerIcon(capitalmarker);
                if(capitalicon == null) {
                    severe("Invalid capitalicon: " + capitalmarker);
                    capitalicon = markerapi.getMarkerIcon("king");
                }
            }
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class TownyUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateTowns();
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();
    private Map<String, Marker> resmark = new HashMap<String, Marker>();
    
    private String formatInfoWindow(Town town) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replaceAll("%regionname%", town.getName());
        v = v.replaceAll("%playerowners%", town.hasMayor()?town.getMayor().getName():"");
        String res = "";
        for(Resident r : town.getResidents()) {
        	if(res.length()>0) res += ",";
        	res += r.getName();
        }
        v = v.replaceAll("%playermembers%", res);
        String mgrs = "";
        for(Resident r : town.getAssistants()) {
            if(mgrs.length()>0) mgrs += ",";
            mgrs += r.getName();
        }
        v = v.replaceAll("%playermanagers%", res);
        
        String nation = "";
		try {
			if(town.hasNation())
				nation = town.getNation().getName();
		} catch (Exception e) {
		}
        v = v.replaceAll("%nation%", nation);
        /* Build flags */
        String flgs = "hasUpkeep: " + town.hasUpkeep();
        flgs += "<br/>pvp: " + town.isPVP();
        flgs += "<br/>mobs: " + town.hasMobs();
        flgs += "<br/>public: " + town.isPublic();
        flgs += "<br/>explosion: " + town.isBANG();
        flgs += "<br/>fire: " + town.isFire();
        flgs += "<br/>capital: " + town.isCapital();
        v = v.replaceAll("%flags%", flgs);
        return v;
    }
    
    private boolean isVisible(String id) {
        if((visible != null) && (visible.size() > 0)) {
            if(visible.contains(id) == false) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id))
                return false;
        }
        return true;
    }
    
    private void addStyle(String resid, AreaMarker m) {
        AreaStyle as = cusstyle.get(resid);
        if(as == null)
            as = defstyle;
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
    }
    
    private MarkerIcon getMarkerIcon(Town town) {
        String id = town.getName();
        AreaStyle as = cusstyle.get(id);
        if(as == null) {
            as = defstyle;
        }
        if(town.isCapital() && (as.capitalicon != null))
            return as.capitalicon;
        else
            return as.homeicon;
    }
 
    enum direction { XPLUS, ZPLUS, XMINUS, ZMINUS };
        
    /**
     * Find all contiguous blocks, set in target and clear in source
     */
    private int floodFillTarget(TileFlags src, TileFlags dest, int x, int y) {
        int cnt = 0;
        if(src.getFlag(x, y)) { /* Set in src */
            src.setFlag(x, y, false);   /* Clear source */
            dest.setFlag(x, y, true);   /* Set in destination */
            cnt++;
            cnt += floodFillTarget(src, dest, x+1, y); /* Fill adjacent blocks */
            cnt += floodFillTarget(src, dest, x-1, y);
            cnt += floodFillTarget(src, dest, x, y+1);
            cnt += floodFillTarget(src, dest, x, y-1);
        }
        return cnt;
    }
    
    /* Handle specific town */
    private void handleTown(Town town, Map<String, AreaMarker> newmap, Map<String, Marker> newmark) {
        String name = town.getName();
        double[] x = null;
        double[] z = null;
        int poly_index = 0; /* Index of polygon for given town */
        
        /* Build popup */
        String desc = formatInfoWindow(town);
        
        /* Handle areas */
        if(isVisible(name)) {
        	List<TownBlock> blocks = town.getTownBlocks();
        	if(blocks.isEmpty())
        	    return;
        	HashMap<String, TileFlags> blkmaps = new HashMap<String, TileFlags>();
            LinkedList<TownBlock> nodevals = new LinkedList<TownBlock>();
            TownyWorld curworld = null;
            TileFlags curblks = null;
        	/* Loop through blocks: set flags on blockmaps for worlds */
        	for(TownBlock b : blocks) {
        	    if(b.getWorld() != curworld) { /* Not same world */
        	        String wname = b.getWorld().getName();
        	        curblks = blkmaps.get(wname);  /* Find existing */
        	        if(curblks == null) {
        	            curblks = new TileFlags();
        	            blkmaps.put(wname, curblks);   /* Add fresh one */
        	        }
        	        curworld = b.getWorld();
        	    }
        	    curblks.setFlag(b.getX(), b.getZ(), true); /* Set flag for block */
        	    nodevals.addLast(b);
        	}
            /* Loop through until we don't find more areas */
            while(nodevals != null) {
                LinkedList<TownBlock> ournodes = null;
                LinkedList<TownBlock> newlist = null;
                TileFlags ourblks = null;
                int minx = Integer.MAX_VALUE;
                int minz = Integer.MAX_VALUE;
                for(TownBlock node : nodevals) {
                    int nodex = node.getX();
                    int nodez = node.getZ();
                    if(ourblks == null) {   /* If not started, switch to world for this block first */
                        if(node.getWorld() != curworld) {
                            curworld = node.getWorld();
                            curblks = blkmaps.get(curworld.getName());
                        }
                    }
                    /* If we need to start shape, and this block is not part of one yet */
                    if((ourblks == null) && curblks.getFlag(nodex, nodez)) {
                        ourblks = new TileFlags();  /* Create map for shape */
                        ournodes = new LinkedList<TownBlock>();
                        floodFillTarget(curblks, ourblks, nodex, nodez);   /* Copy shape */
                        ournodes.add(node); /* Add it to our node list */
                        minx = nodex; minz = nodez;
                    }
                    /* If shape found, and we're in it, add to our node list */
                    else if((ourblks != null) && (node.getWorld() == curworld) &&
                        (ourblks.getFlag(nodex, nodez))) {
                        ournodes.add(node);
                        if(nodex < minx) {
                            minx = nodex; minz = nodez;
                        }
                        else if((nodex == minx) && (nodez < minz)) {
                            minz = nodez;
                        }
                    }
                    else {  /* Else, keep it in the list for the next polygon */
                        if(newlist == null) newlist = new LinkedList<TownBlock>();
                        newlist.add(node);
                    }
                }
                nodevals = newlist; /* Replace list (null if no more to process) */
                if(ourblks != null) {
                    /* Trace outline of blocks - start from minx, minz going to x+ */
                    int init_x = minx;
                    int init_z = minz;
                    int cur_x = minx;
                    int cur_z = minz;
                    direction dir = direction.XPLUS;
                    ArrayList<int[]> linelist = new ArrayList<int[]>();
                    linelist.add(new int[] { init_x, init_z } ); // Add start point
                    while((cur_x != init_x) || (cur_z != init_z) || (dir != direction.ZMINUS)) {
                        switch(dir) {
                            case XPLUS: /* Segment in X+ direction */
                                if(!ourblks.getFlag(cur_x+1, cur_z)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                                    dir = direction.ZPLUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x+1, cur_z-1)) {  /* Straight? */
                                    cur_x++;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                                    dir = direction.ZMINUS;
                                    cur_x++; cur_z--;
                                }
                                break;
                            case ZPLUS: /* Segment in Z+ direction */
                                if(!ourblks.getFlag(cur_x, cur_z+1)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                                    dir = direction.XMINUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x+1, cur_z+1)) {  /* Straight? */
                                    cur_z++;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                                    dir = direction.XPLUS;
                                    cur_x++; cur_z++;
                                }
                                break;
                            case XMINUS: /* Segment in X- direction */
                                if(!ourblks.getFlag(cur_x-1, cur_z)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                                    dir = direction.ZMINUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x-1, cur_z+1)) {  /* Straight? */
                                    cur_x--;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                                    dir = direction.ZPLUS;
                                    cur_x--; cur_z++;
                                }
                                break;
                            case ZMINUS: /* Segment in Z- direction */
                                if(!ourblks.getFlag(cur_x, cur_z-1)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                                    dir = direction.XPLUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x-1, cur_z-1)) {  /* Straight? */
                                    cur_z--;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                                    dir = direction.XMINUS;
                                    cur_x--; cur_z--;
                                }
                                break;
                        }
                    }
                    /* Build information for specific area */
                    String polyid = town.getName() + "__" + poly_index;
                    int sz = linelist.size();
                    x = new double[sz];
                    z = new double[sz];
                    for(int i = 0; i < sz; i++) {
                        int[] line = linelist.get(i);
                        x[i] = (double)line[0] * (double)townblocksize;
                        z[i] = (double)line[1] * (double)townblocksize;
                    }
                    /* Find existing one */
                    AreaMarker m = resareas.remove(polyid); /* Existing area? */
                    if(m == null) {
                        m = set.createAreaMarker(polyid, name, false, curworld.getName(), x, z, false);
                        if(m == null) {
                            info("error adding area marker " + polyid);
                            return;
                        }
                    }
                    else {
                        m.setCornerLocations(x, z); /* Replace corner locations */
                        m.setLabel(name);   /* Update label */
                    }
                    m.setDescription(desc); /* Set popup */
                
                    /* Set line and fill properties */
                    addStyle(town.getName(), m);
    
                    /* Add to map */
                    newmap.put(polyid, m);
                    poly_index++;
                }
            }
            /* Now, add marker for home block */
            TownBlock blk = null;
            try {
                blk = town.getHomeBlock();
            } catch(Exception ex) {
                severe("getHomeBlock exception " + ex);
            }
            if(blk != null) {
                String markid = town.getName() + "__home";
                MarkerIcon ico = getMarkerIcon(town);
                if(ico != null) {
                    Marker home = resmark.remove(markid);
                    double xx = townblocksize*blk.getX() + (townblocksize/2);
                    double zz = townblocksize*blk.getZ() + (townblocksize/2);
                    if(home == null) {
                        home = set.createMarker(markid, name + " [home]", blk.getWorld().getName(), 
                            xx, 64, zz, ico, false);
                        if(home == null)
                            return;
                    }
                    else {
                        home.setLocation(blk.getWorld().getName(), xx, 64, zz);
                        home.setLabel(name + " [home]");   /* Update label */
                        home.setMarkerIcon(ico);
                    }
                    home.setDescription(desc); /* Set popup */
                    newmark.put(markid, home);
                }
            }
        }
    }
    
    /* Update Towny information */
    private void updateTowns() {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
        Map<String,Marker> newmark = new HashMap<String,Marker>(); /* Build new map */
        
        /* Loop through towns */
        List<Town> towns = tuniv.getTowns();
        for(Town t : towns) {
    		handleTown(t, newmap, newmark);
        }
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        for(Marker oldm : resmark.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        resmark = newmark;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new TownyUpdate(), updperiod);
        
    }
    
    private class OurServerListener extends ServerListener {
        @Override
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("Towny")) {
                if(dynmap.isEnabled() && towny.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get Towny */
        Plugin p = pm.getPlugin("Towny");
        if(p == null) {
            severe("Cannot find Towny!");
            return;
        }
        towny = (Towny)p;
        /* If both enabled, activate */
        if(dynmap.isEnabled() && towny.isEnabled())
            activate();
        else
            getServer().getPluginManager().registerEvent(Type.PLUGIN_ENABLE, new OurServerListener(), Priority.Monitor, this);        
    }
    
    private void activate() {
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Connect to towny API */
        tuniv = towny.getTownyUniverse();
        townblocksize = Coord.getCellSize();
        
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.createMarkerSet("towny.markerset", cfg.getString("layer.name", "Towny"), null, false);
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        List vis = cfg.getList("visibleregions");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List hid = cfg.getList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new TownyUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
