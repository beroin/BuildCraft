/**
 * Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.robotics;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.ChunkEvent;
import buildcraft.api.core.BCLog;
import buildcraft.api.robots.DockingStation;
import buildcraft.api.robots.EntityRobotBase;
import buildcraft.api.robots.IRobotRegistry;
import buildcraft.api.robots.ResourceId;
import buildcraft.api.robots.RobotManager;

public class RobotRegistry extends WorldSavedData implements IRobotRegistry {

	protected World world;
	protected HashMap<StationIndex, DockingStation> stations = new HashMap<StationIndex, DockingStation>();

	private long nextRobotID = Long.MIN_VALUE;

	private HashMap<Long, EntityRobot> robotsLoaded = new HashMap<Long, EntityRobot>();
	private HashMap<ResourceId, Long> resourcesTaken = new HashMap<ResourceId, Long>();
	private HashMap<Long, HashSet<ResourceId>> resourcesTakenByRobot = new HashMap<Long, HashSet<ResourceId>>();

	private HashMap<Long, HashSet<StationIndex>> stationsTakenByRobot = new HashMap<Long, HashSet<StationIndex>>();

	public RobotRegistry(String id) {
		super(id);
	}

	@Override
	public long getNextRobotId() {
		long result = nextRobotID;

		nextRobotID = nextRobotID + 1;

		return result;
	}

	@Override
	public void registerRobot(EntityRobotBase robot) {
		markDirty();

		if (robot.getRobotId() == EntityRobotBase.NULL_ROBOT_ID) {
			((EntityRobot) robot).setUniqueRobotId(getNextRobotId());
		}
		if (robotsLoaded.containsKey(robot.getRobotId())) {
			BCLog.logger.warn("Robot with id %d was not unregistered properly", robot.getRobotId());
		}

		robotsLoaded.put(robot.getRobotId(), (EntityRobot) robot);
	}

	@Override
	public void killRobot(EntityRobotBase robot) {
		markDirty();

		releaseResources(robot, true);
		robotsLoaded.remove(robot.getRobotId());
	}

	@Override
	public void unloadRobot(EntityRobotBase robot) {
		markDirty();

		releaseResources(robot, false, true);
		robotsLoaded.remove(robot.getRobotId());
	}

	@Override
	public EntityRobot getLoadedRobot(long id) {
		if (robotsLoaded.containsKey(id)) {
			return robotsLoaded.get(id);
		} else {
			return null;
		}
	}

	@Override
	public synchronized boolean isTaken(ResourceId resourceId) {
		return robotIdTaking(resourceId) != EntityRobotBase.NULL_ROBOT_ID;
	}

	@Override
	public synchronized long robotIdTaking(ResourceId resourceId) {
		if (!resourcesTaken.containsKey(resourceId)) {
			return EntityRobotBase.NULL_ROBOT_ID;
		}

		long robotId = resourcesTaken.get(resourceId);

		if (robotsLoaded.containsKey(robotId) && !robotsLoaded.get(robotId).isDead) {
			return robotId;
		} else {
			// If the robot is either not loaded or dead, the resource is not
			// actively used anymore. Release it.
			release(resourceId);
			return EntityRobotBase.NULL_ROBOT_ID;
		}
	}

	@Override
	public synchronized EntityRobot robotTaking(ResourceId resourceId) {
		long robotId = robotIdTaking(resourceId);

		if (robotId == EntityRobotBase.NULL_ROBOT_ID || !robotsLoaded.containsKey(robotId)) {
			return null;
		} else {
			return robotsLoaded.get(robotId);
		}
	}

	@Override
	public synchronized boolean take(ResourceId resourceId, EntityRobotBase robot) {
		markDirty();

		return take(resourceId, robot.getRobotId());
	}

	@Override
	public synchronized boolean take(ResourceId resourceId, long robotId) {
		if (resourceId == null) {
			return false;
		}

		markDirty();

		if (!resourcesTaken.containsKey(resourceId)) {
			resourcesTaken.put(resourceId, robotId);

			if (!resourcesTakenByRobot.containsKey(robotId)) {
				resourcesTakenByRobot.put(robotId, new HashSet<ResourceId>());
			}

			resourcesTakenByRobot.get(robotId).add(resourceId);

			resourceId.taken(robotId);

			return true;
		} else {
			return false;
		}
	}

	@Override
	public synchronized void release(ResourceId resourceId) {
		if (resourceId == null) {
			return;
		}

		markDirty();

		if (resourcesTaken.containsKey(resourceId)) {
			long robotId = resourcesTaken.get(resourceId);

			resourcesTakenByRobot.get(resourcesTaken.get(resourceId)).remove(resourceId);
			resourcesTaken.remove(resourceId);
			resourceId.released(robotId);
		}
	}

	@Override
	public synchronized void releaseResources(EntityRobotBase robot) {
		releaseResources(robot, false);
	}

	private synchronized void releaseResources(EntityRobotBase robot, boolean forceAll) {
		releaseResources(robot, forceAll, false);
	}

	private synchronized void releaseResources(EntityRobotBase robot, boolean forceAll, boolean resetEntities) {
		markDirty();

		if (resourcesTakenByRobot.containsKey(robot.getRobotId())) {
			HashSet<ResourceId> resourceSet = (HashSet<ResourceId>) resourcesTakenByRobot.get(robot.getRobotId())
					.clone();

			for (ResourceId id : resourceSet) {
				release(id);
			}

			resourcesTakenByRobot.remove(robot.getRobotId());
		}

		if (stationsTakenByRobot.containsKey(robot.getRobotId())) {
			HashSet<StationIndex> stationSet = (HashSet<StationIndex>) stationsTakenByRobot.get(robot.getRobotId())
					.clone();

			for (StationIndex s : stationSet) {
				DockingStation d = stations.get(s);

				if (d != null) {
					if (!d.canRelease()) {
						if (forceAll) {
							d.unsafeRelease(robot);
						} else if (resetEntities && d.robotIdTaking() == robot.getRobotId()) {
							d.invalidateRobotTakingEntity();
						}
					} else {
						d.unsafeRelease(robot);
					}
				}
			}

			if (forceAll) {
				stationsTakenByRobot.remove(robot.getRobotId());
			}
		}
	}

	@Override
	public synchronized DockingStation getStation(int x, int y, int z, ForgeDirection side) {
		StationIndex index = new StationIndex(side, x, y, z);

		if (stations.containsKey(index)) {
			return stations.get(index);
		} else {
			return null;
		}
	}

	@Override
	public synchronized Collection<DockingStation> getStations() {
		return stations.values();
	}

	@Override
	public synchronized void registerStation(DockingStation station) {
		markDirty();

		StationIndex index = new StationIndex(station);

		if (stations.containsKey(index)) {
			throw new InvalidParameterException("Station " + index + " already registerd");
		} else {
			stations.put(index, station);
		}
	}

	@Override
	public synchronized void removeStation(DockingStation station) {
		markDirty();

		StationIndex index = new StationIndex(station);

		if (stations.containsKey(index)) {
			if (station.robotTaking() != null) {
				if (!station.isMainStation()) {
					station.robotTaking().undock();
				} else {
					station.robotTaking().setMainStation(null);
				}
			} else if (station.robotIdTaking() != EntityRobotBase.NULL_ROBOT_ID) {
				if (stationsTakenByRobot.get(station.robotIdTaking()) != null) {
					stationsTakenByRobot.get(station.robotIdTaking()).remove(index);
				}
			}

			stations.remove(index);
		}
	}

	@Override
	public synchronized void take(DockingStation station, long robotId) {
		if (!stationsTakenByRobot.containsKey(robotId)) {
			stationsTakenByRobot.put(robotId, new HashSet<StationIndex>());
		}

		stationsTakenByRobot.get(robotId).add(new StationIndex(station));
	}

	@Override
	public synchronized void release(DockingStation station, long robotId) {
		if (stationsTakenByRobot.containsKey(robotId)) {
			stationsTakenByRobot.get(robotId).remove(new StationIndex(station));
		}
	}

	@Override
	public synchronized void writeToNBT(NBTTagCompound nbt) {
		nbt.setLong("nextRobotID", nextRobotID);

		NBTTagList resourceList = new NBTTagList();

		for (Map.Entry<ResourceId, Long> e : resourcesTaken.entrySet()) {
			NBTTagCompound cpt = new NBTTagCompound();
			NBTTagCompound resourceId = new NBTTagCompound();
			e.getKey().writeToNBT(resourceId);
			cpt.setTag("resourceId", resourceId);
			cpt.setLong("robotId", e.getValue());

			resourceList.appendTag(cpt);
		}

		nbt.setTag("resourceList", resourceList);

		NBTTagList stationList = new NBTTagList();

		for (Map.Entry<StationIndex, DockingStation> e : stations.entrySet()) {
			NBTTagCompound cpt = new NBTTagCompound();
			e.getValue().writeToNBT(cpt);
			cpt.setString("stationType", RobotManager.getDockingStationName(e.getValue().getClass()));
			stationList.appendTag(cpt);
		}

		nbt.setTag("stationList", stationList);
	}

	@Override
	public synchronized void readFromNBT(NBTTagCompound nbt) {
		nextRobotID = nbt.getLong("nextRobotID");

		NBTTagList resourceList = nbt.getTagList("resourceList", Constants.NBT.TAG_COMPOUND);

		for (int i = 0; i < resourceList.tagCount(); ++i) {
			NBTTagCompound cpt = resourceList.getCompoundTagAt(i);
			ResourceId resourceId = ResourceId.load(cpt.getCompoundTag("resourceId"));
			long robotId = cpt.getLong("robotId");

			take(resourceId, robotId);
		}

		NBTTagList stationList = nbt.getTagList("stationList", Constants.NBT.TAG_COMPOUND);

		for (int i = 0; i < stationList.tagCount(); ++i) {
			NBTTagCompound cpt = stationList.getCompoundTagAt(i);

			Class<? extends DockingStation> cls = null;

			if (!cpt.hasKey("stationType")) {
				cls = DockingStationPipe.class;
			} else {
				cls = RobotManager.getDockingStationByName(cpt.getString("stationType"));
				if (cls == null) {
					BCLog.logger.error("Could not load docking station of type "
							+ nbt.getString("stationType"));
					continue;
				}
			}

			try {
				DockingStation station = cls.newInstance();
				station.readFromNBT(cpt);

				registerStation(station);

				if (station.linkedId() != EntityRobotBase.NULL_ROBOT_ID) {
					take(station, station.linkedId());
				}
			} catch (Exception e) {
				BCLog.logger.error("Could not load docking station", e);
			}
		}
	}

	@SubscribeEvent
	public void onChunkUnload(ChunkEvent.Unload e) {
		if (e.world == this.world) {
			for (EntityRobot robot : new ArrayList<EntityRobot>(robotsLoaded.values())) {
				if (!e.world.loadedEntityList.contains(robot)) {
					robot.onChunkUnload();
				}
			}
		}
	}

	/**
	 * This function is a wrapper for markDirty(), done this way due to
	 * obfuscation issues.
	 */
	@Override
	public void registryMarkDirty() {
		markDirty();
	}
}
