// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.osm;

import java.awt.geom.Area;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.OsmPrimitiveVisitor;
import org.openstreetmap.josm.data.osm.visitor.PrimitiveVisitor;
import org.openstreetmap.josm.data.projection.Projecting;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * One node data, consisting of one world coordinate waypoint.
 *
 * @author imi
 */
public final class Node extends OsmPrimitive implements INode {

    static final UniqueIdGenerator idGenerator = new UniqueIdGenerator();

    /*
     * We "inline" lat/lon rather than using a LatLon-object => reduces memory footprint
     */
    private double lat = Double.NaN;
    private double lon = Double.NaN;

    /*
     * the cached projected coordinates
     */
    private double east = Double.NaN;
    private double north = Double.NaN;
    /**
     * The cache key to use for {@link #east} and {@link #north}.
     */
    private Object eastNorthCacheKey;

    @Override
    public void setCoor(LatLon coor) {
        updateCoor(coor, null);
    }

    @Override
    public void setEastNorth(EastNorth eastNorth) {
        updateCoor(null, eastNorth);
    }

    private void updateCoor(LatLon coor, EastNorth eastNorth) {
        if (getDataSet() != null) {
            boolean locked = writeLock();
            try {
                getDataSet().fireNodeMoved(this, coor, eastNorth);
            } finally {
                writeUnlock(locked);
            }
        } else {
            setCoorInternal(coor, eastNorth);
        }
    }

    /**
     * Returns lat/lon coordinates of this node, or {@code null} unless {@link #isLatLonKnown()}
     * @return lat/lon coordinates of this node, or {@code null} unless {@link #isLatLonKnown()}
     */
    @Override
    public LatLon getCoor() {
        if (!isLatLonKnown()) {
            return null;
        } else {
            return new LatLon(lat, lon);
        }
    }

    @Override
    public double lat() {
        return lat;
    }

    @Override
    public double lon() {
        return lon;
    }

    @Override
    public EastNorth getEastNorth(Projecting projection) {
        if (!isLatLonKnown()) return null;

        if (Double.isNaN(east) || Double.isNaN(north) || !Objects.equals(projection.getCacheKey(), eastNorthCacheKey)) {
            // projected coordinates haven't been calculated yet,
            // so fill the cache of the projected node coordinates
            EastNorth en = projection.latlon2eastNorth(this);
            this.east = en.east();
            this.north = en.north();
            this.eastNorthCacheKey = projection.getCacheKey();
        }
        return new EastNorth(east, north);
    }

    /**
     * To be used only by Dataset.reindexNode
     * @param coor lat/lon
     * @param eastNorth east/north
     */
    void setCoorInternal(LatLon coor, EastNorth eastNorth) {
        if (coor != null) {
            this.lat = coor.lat();
            this.lon = coor.lon();
            invalidateEastNorthCache();
        } else if (eastNorth != null) {
            LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(eastNorth);
            this.lat = ll.lat();
            this.lon = ll.lon();
            this.east = eastNorth.east();
            this.north = eastNorth.north();
            this.eastNorthCacheKey = ProjectionRegistry.getProjection().getCacheKey();
        } else {
            this.lat = Double.NaN;
            this.lon = Double.NaN;
            invalidateEastNorthCache();
            if (isVisible()) {
                setIncomplete(true);
            }
        }
    }

    Node(long id, boolean allowNegative) {
        super(id, allowNegative);
    }

    /**
     * Constructs a new local {@code Node} with id 0.
     */
    public Node() {
        this(0, false);
    }

    /**
     * Constructs an incomplete {@code Node} object with the given id.
     * @param id The id. Must be &gt;= 0
     * @throws IllegalArgumentException if id &lt; 0
     */
    public Node(long id) {
        super(id, false);
    }

    /**
     * Constructs a new {@code Node} with the given id and version.
     * @param id The id. Must be &gt;= 0
     * @param version The version
     * @throws IllegalArgumentException if id &lt; 0
     */
    public Node(long id, int version) {
        super(id, version, false);
    }

    /**
     * Constructs an identical clone of the argument.
     * @param clone The node to clone
     * @param clearMetadata If {@code true}, clears the OSM id and other metadata as defined by {@link #clearOsmMetadata}.
     * If {@code false}, does nothing
     */
    public Node(Node clone, boolean clearMetadata) {
        super(clone.getUniqueId(), true /* allow negative IDs */);
        cloneFrom(clone);
        if (clearMetadata) {
            clearOsmMetadata();
        }
    }

    /**
     * Constructs an identical clone of the argument (including the id).
     * @param clone The node to clone, including its id
     */
    public Node(Node clone) {
        this(clone, false);
    }

    /**
     * Constructs a new {@code Node} with the given lat/lon with id 0.
     * @param latlon The {@link LatLon} coordinates
     */
    public Node(LatLon latlon) {
        super(0, false);
        setCoor(latlon);
    }

    /**
     * Constructs a new {@code Node} with the given east/north with id 0.
     * @param eastNorth The {@link EastNorth} coordinates
     */
    public Node(EastNorth eastNorth) {
        super(0, false);
        setEastNorth(eastNorth);
    }

    @Override
    void setDataset(DataSet dataSet) {
        super.setDataset(dataSet);
        if (!isIncomplete() && isVisible() && !isLatLonKnown())
            throw new DataIntegrityProblemException("Complete node with null coordinates: " + toString());
    }

    @Override
    public void accept(OsmPrimitiveVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public void accept(PrimitiveVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public void cloneFrom(OsmPrimitive osm, boolean copyChildren) {
        if (!(osm instanceof Node))
            throw new IllegalArgumentException("Not a node: " + osm);
        boolean locked = writeLock();
        try {
            super.cloneFrom(osm, copyChildren);
            setCoor(((Node) osm).getCoor());
        } finally {
            writeUnlock(locked);
        }
    }

    /**
     * Merges the technical and semantical attributes from <code>other</code> onto this.
     *
     * Both this and other must be new, or both must be assigned an OSM ID. If both this and <code>other</code>
     * have an assigend OSM id, the IDs have to be the same.
     *
     * @param other the other primitive. Must not be null.
     * @throws IllegalArgumentException if other is null.
     * @throws DataIntegrityProblemException if either this is new and other is not, or other is new and this is not
     * @throws DataIntegrityProblemException if other is new and other.getId() != this.getId()
     */
    @Override
    public void mergeFrom(OsmPrimitive other) {
        if (!(other instanceof Node))
            throw new IllegalArgumentException("Not a node: " + other);
        boolean locked = writeLock();
        try {
            super.mergeFrom(other);
            if (!other.isIncomplete()) {
                setCoor(((Node) other).getCoor());
            }
        } finally {
            writeUnlock(locked);
        }
    }

    @Override
    public void load(PrimitiveData data) {
        if (!(data instanceof NodeData))
            throw new IllegalArgumentException("Not a node data: " + data);
        boolean locked = writeLock();
        try {
            super.load(data);
            setCoor(((NodeData) data).getCoor());
        } finally {
            writeUnlock(locked);
        }
    }

    @Override
    public NodeData save() {
        NodeData data = new NodeData();
        saveCommonAttributes(data);
        if (!isIncomplete()) {
            data.setCoor(getCoor());
        }
        return data;
    }

    @Override
    public String toString() {
        String coorDesc = isLatLonKnown() ? "lat="+lat+",lon="+lon : "";
        return "{Node id=" + getUniqueId() + " version=" + getVersion() + ' ' + getFlagsAsString() + ' ' + coorDesc+'}';
    }

    @Override
    public boolean hasEqualSemanticAttributes(OsmPrimitive other, boolean testInterestingTagsOnly) {
        return (other instanceof Node)
                && hasEqualSemanticFlags(other)
                && hasEqualCoordinates((Node) other)
                && super.hasEqualSemanticAttributes(other, testInterestingTagsOnly);
    }

    private boolean hasEqualCoordinates(Node other) {
        if (this.isLatLonKnown() && other.isLatLonKnown()) {
            return this.equalsEpsilon(other);
        }
        return false;
    }

    @Override
    public OsmPrimitiveType getType() {
        return OsmPrimitiveType.NODE;
    }

    @Override
    public BBox getBBox() {
        return new BBox(lon, lat);
    }

    @Override
    protected void addToBBox(BBox box, Set<PrimitiveId> visited) {
        box.add(lon, lat);
    }

    @Override
    public void updatePosition() {
        // Do nothing
    }

    @Override
    public boolean isDrawable() {
        // Not possible to draw a node without coordinates.
        return super.isDrawable() && isLatLonKnown();
    }

    @Override
    public boolean isReferredByWays(int n) {
        return isNodeReferredByWays(n);
    }

    /**
     * Invoke to invalidate the internal cache of projected east/north coordinates.
     * Coordinates are reprojected on demand when the {@link #getEastNorth()} is invoked
     * next time.
     */
    public void invalidateEastNorthCache() {
        this.east = Double.NaN;
        this.north = Double.NaN;
        this.eastNorthCacheKey = null;
    }

    @Override
    public boolean concernsArea() {
        // A node cannot be an area
        return false;
    }

    @Override
    public boolean isOutsideDownloadArea() {
        if (isNewOrUndeleted() || getDataSet() == null)
            return false;
        Area area = getDataSet().getDataSourceArea();
        if (area == null)
            return false;
        LatLon coor = getCoor();
        return coor != null && !coor.isIn(area);
    }

    /**
     * Replies the set of referring ways.
     * @return the set of referring ways
     * @since 12031
     */
    public List<Way> getParentWays() {
        return referrers(Way.class).collect(Collectors.toList());
    }

    /**
     * Determines if this node is outside of the world. See also #13538.
     * @return <code>true</code>, if the coordinate is outside the world, compared by using lat/lon and east/north
     * @since 14960
     */
    public boolean isOutSideWorld() {
        if (this.isLatLonKnown()) {
            Bounds b = ProjectionRegistry.getProjection().getWorldBoundsLatLon();
            if (lat() < b.getMinLat() || lat() > b.getMaxLat() || lon() < b.getMinLon() || lon() > b.getMaxLon()) {
                return true;
            }
            if (!ProjectionRegistry.getProjection().latlon2eastNorth(this).equalsEpsilon(getEastNorth(), 1.0)) {
                // we get here if a node was moved or created left from -180 or right from +180
                return true;
            }
        }
        return false;
    }

    @Override
    public UniqueIdGenerator getIdGenerator() {
        return idGenerator;
    }

    @Override
    protected void updateDirectionFlags() {
        // Nodes do not need/have a direction, greatly improves performance, see #18886
    }
}
