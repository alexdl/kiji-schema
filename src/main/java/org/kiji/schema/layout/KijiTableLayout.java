/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.layout;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificData;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.schema.KijiCellFormat;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.NoSuchColumnException;
import org.kiji.schema.avro.CellSchema;
import org.kiji.schema.avro.ColumnDesc;
import org.kiji.schema.avro.FamilyDesc;
import org.kiji.schema.avro.LocalityGroupDesc;
import org.kiji.schema.avro.TableLayoutDesc;
import org.kiji.schema.layout.KijiTableLayout.LocalityGroupLayout.FamilyLayout;
import org.kiji.schema.layout.KijiTableLayout.LocalityGroupLayout.FamilyLayout.ColumnLayout;
import org.kiji.schema.layout.impl.ColumnId;
import org.kiji.schema.util.FromJson;
import org.kiji.schema.util.JavaIdentifiers;
import org.kiji.schema.util.KijiNameValidator;
import org.kiji.schema.util.ToJson;

/**
 * Layout of a Kiji table.
 *
 * <p>
 *   Kiji uses the term <i>layout</i> to describe the structure of a table.
 *   Kiji does not use the term <i>schema</i> to avoid confusion with Avro schemas or XML schemas.
 * </p>
 *
 * <p>
 *   KijiTableLayout wraps a layout descriptor represented as a
 *   {@link org.kiji.schema.avro.TableLayoutDesc TableLayoutDesc} Avro record.
 *   KijiTableLayout provides strict validation and accessors to navigate through the layout.
 * </p>
 *
 * <h1>Overall structure</h1>
 * <p>At the top-level, a table contains:
 * <ul>
 *   <li>the table name and description;</li>
 *   <li>how row keys are encoded;</li>
 *   <li>the table locality groups.</li>
 * </ul>
 * </p>
 *
 * <p>Each locality group has:
 * <ul>
 *   <li>a primary name, unique within the table, a description and some name aliases;</li>
 *   <li>whether the data is to be stored in memory or on disk;</li>
 *   <li>data retention lifetime;</li>
 *   <li>maximum number of versions to keep;</li>
 *   <li>type of compression;</li>
 *   <li>column families stored in this locality group</li>
 * </ul>
 * </p>
 *
 * <p>Each column family has:
 * <ul>
 *   <li>a primary name, globally unique within the table,
 *       a description and some name aliases;</li>
 *   <li>for map-type families, the Avro schema of the cell values;</li>
 *   <li>for group-type families, the collection of columns in the group.</li>
 * </ul>
 * </p>
 *
 * <p>Each column in a group-type family has:
 * <ul>
 *   <li>a primary name, unique within the family, a description and some name aliases;</li>
 *   <li>an Avro schema.</li>
 * </ul>
 * </p>
 *
 * <h1>Layout descriptors</h1>
 *
 * Layout descriptors are represented using
 * {@link org.kiji.schema.avro.TableLayoutDesc TableLayoutDesc} Avro records.
 * Layout descriptors come in two flavors: <i>concrete layouts</i> and <i>layout updates</i>.
 *
 * <h2>Concrete layout descriptors</h2>
 * A concrete layout descriptors is an absolute, standalone description of a table layout, which
 * does not reference or build upon any previous version of the table layout. Column IDs have
 * been assigned to all locality groups, families and columns.
 *
 * <h2>Layout update descriptors</h2>
 * A table layout update descriptor builds on a reference table layout, and describes layout
 * modification to apply on the reference layout.
 * The reference table layout is specified by writing the ID of the reference layout
 * ({@link TableLayoutDesc#layout_id}) into the {@link TableLayoutDesc#reference_layout}.
 * This mechanism prevents race conditions when updating the layout of a table.
 * The first layout of a newly created table has no reference layout.
 *
 * <p>During a layout update, the user may delete or declare new locality groups, families and/or
 * columns. The user may also modify existing locality groups, families and/or columns.
 *
 * <p>The result of applying a layout update on top of a concrete reference layout is a new
 * concrete layout.
 *
 * <h1>Names</h1>
 *
 * <p> Layout entities are uniquely identified through their primary names.
 * Name aliases can be freely updated, as long as uniqueness requirements are met.
 * Primary name updates must be explicitly annotated by setting the {@code renamedFrom} field of
 * the entity being renamed.
 * The name of a table cannot be changed.
 *
 * <p> Names of tables, locality groups, families and column qualifiers must be valid identifiers.
 * Name validation occurs in {@link org.kiji.schema.util.KijiNameValidator KijiNameValidator}.
 *
 * <h1>Validation rules</h1>
 *
 * <ul>
 *   <li> Table names, locality group names, family names, and column names in a group-type family
 *        must be valid identifiers (no punctuation or symbols).
 *        Note: map-type family qualifiers are free-form, but do never appear in a table layout.
 *   <li> Locality group names and aliases must be unique within the table.
 *   <li> Family names and aliases must be unique within the table.
 *   <li> Group-type family qualifiers must be unique within the family.
 *   <li> The type of a family (map-type or group-type) cannot be changed.
 *   <li> A family cannot be moved into a different locality group.
 *   <li> The encoding of Kiji cells (hash, UID, final) cannot be modified.
 *   <li> The schema of a Kiji cell can only be changed to a schema that is compatible with
 *        all the former schemas of the column. Schema compatibility requires that the new schema
 *        allows decoding all former schemas associated to the column or the map-type family.
 * </ul>
 *
 * <h1>Row keys encoding</h1>
 *
 * A row in a Kiji table is identified by its Kiji row key. Kiji row keys are converted into HBase
 * row keys according to the row key encoding specified in the table layout:
 * <ul>
 *   <li> Raw encoding: the user has direct control over the encoding of row keys in the HBase
 *        table. In other words, the HBase row key is exactly the Kiji row key.
 *        See {@link org.kiji.schema.impl.RawEntityId RawEntityId}.
 *   </li>
 *   <li> Hashed: the HBase row key is computed as a hash of the Kiji row key.
 *        See {@link org.kiji.schema.impl.HashedEntityId HashedEntityId}.
 *   </li>
 *   <li> Hash-prefixed: the HBase row key is computed as the concatenation of a hash of the
 *        Kiji row key and the Kiji row key itself.
 *        See {@link org.kiji.schema.impl.HashPrefixedEntityId HashPrefixedEntityId}.
 *   </li>
 * </ul>
 *
 * Hashing allows to spread the rows evenly across all the regions in the table.
 *
 * <h1>Cell schema</h1>
 *
 * Kiji cells are encoded according to a schema specified via
 * {@link org.kiji.schema.avro.CellSchema CellSchema} Avro records.
 * Kiji provides various cell encoding schemes:
 * <ul>
 *   <li> Hash: each Kiji cell is encoded as a hash of the Avro schema, followed by the binary
 *        encoding of the Avro value.
 *   </li>
 *   <li> UID: each Kiji cell is encoded as the unique ID of the Avro schema, followed by the
 *        binary encoding of the Avro value.
 *   </li>
 *   <li> Final: each Kiji cell is encoded as the binary encoding of the Avro value.
 *   </li>
 * </ul>
 * See {@link org.kiji.schema.KijiCellEncoder KijiCellEncoder}
 * and {@link org.kiji.schema.KijiCellDecoder KijiCellDecoder}
 * for more implementation details.
 *
 * <h1>Column IDs</h1>
 *
 * For storage efficiency purposes, Kiji family and column names are translated into short
 * HBase column names.
 * The translation happens in
 *   {@link org.kiji.schema.layout.ColumnNameTranslator ColumnNameTranslator}
 * and relies on
 *   {@link org.kiji.schema.layout.impl.ColumnId ColumnId}.
 * Column IDs are assigned automatically by KijiTableLayout.
 * The user may specify column IDs manually. KijiTableLayout checks the consistency of column IDs.
 *
 * <p>Column IDs cannot be changed (a column ID change is equivalent to deleting the existing column
 * and then re-creating it as a new empty column).
 */
public class KijiTableLayout {
  private static final Logger LOG = LoggerFactory.getLogger(KijiTableLayout.class);

  /** Concrete layout of a locality group. */
  public class LocalityGroupLayout {

    /** Concrete layout of a family. */
    public class FamilyLayout {

      /** Concrete layout of a column. */
      public class ColumnLayout {
        /** Column layout descriptor. */
        private final ColumnDesc mDesc;

        /** Column name and aliases. */
        private final Set<String> mNames;

        /** Column ID. */
        private ColumnId mId = null;

        /**
         * Builds a new column layout instance from a descriptor.
         *
         * @param desc Column descriptor.
         * @param reference Optional reference layout, or null.
         * @throws InvalidLayoutException if the layout is invalid or inconsistent.
         */
        public ColumnLayout(ColumnDesc desc, ColumnLayout reference)
            throws InvalidLayoutException {
          mDesc = Preconditions.checkNotNull(desc);

          final Set<String> names = Sets.newHashSet();
          names.add(desc.getName());
          names.addAll(desc.getAliases());
          mNames = ImmutableSet.copyOf(names);

          if (!isValidName(desc.getName())) {
            throw new InvalidLayoutException(String.format(
                "Invalid column name: '%s'.", desc.getName()));
          }

          for (String name : mNames) {
            if (!isValidAlias(name)) {
              throw new InvalidLayoutException(String.format(
                  "Invalid column alias: '%s'.", name));
            }
          }

          if (desc.getId() > 0) {
            mId = new ColumnId(desc.getId());
          }

          if (reference != null) {
            if ((mId != null) && !mId.equals(reference.getId())) {
              throw new InvalidLayoutException(String.format(
                  "Descriptor for column '%s' has ID %s but reference ID is %s.",
                  getName(), mId, reference.getId()));
            }
            mId = reference.getId();
            desc.setId(mId.getId());
          }

          // Force validation of schema:
          validateAvroSchema(mDesc.getColumnSchema());
        }

        /** @return the Avro descriptor for this column. */
        public ColumnDesc getDesc() {
          return mDesc;
        }

        /** @return the primary name for the column. */
        public String getName() {
          return mDesc.getName();
        }

        /** @return the name and aliases for the column. */
        public Set<String> getNames() {
          return mNames;
        }

        /** @return the ID associated to this column. */
        public ColumnId getId() {
          return mId;
        }

        /**
         * Assigns the ID of this column.
         *
         * @param cid the ID of the column.
         * @return this column.
         */
        private ColumnLayout setId(ColumnId cid) {
          Preconditions.checkArgument(cid.getId() >= 1);
          Preconditions.checkState(null == mId);
          mId = cid;
          mDesc.setId(cid.getId());
          return this;
        }

        /** @return the family this column belongs to. */
        public FamilyLayout getFamily() {
          return FamilyLayout.this;
        }
      }  // class ColumnLayout

      // -------------------------------------------------------------------------------------------

      /** Family layout descriptor. */
      private final FamilyDesc mDesc;

      /** Family name and aliases. */
      private final Set<String> mNames;

      /** Columns in the family. */
      private final ImmutableList<ColumnLayout> mColumns;

      /** Map column qualifier name (no aliases) to column layout. */
      private final ImmutableMap<String, ColumnLayout> mColumnMap;

      /** Bidirectional mapping between column IDs and column names (no aliases). */
      private final BiMap<ColumnId, String> mColumnIdNameMap;

      /** Family ID. */
      private ColumnId mId = null;

      // CSOFF: MethodLengthCheck
      /**
       * Builds a new family layout instance.
       *
       * @param familyDesc Descriptor of the family.
       * @param reference Optional reference family layout, or null.
       * @throws InvalidLayoutException if the layout is invalid or inconsistent.
       */
      public FamilyLayout(FamilyDesc familyDesc, FamilyLayout reference)
          throws InvalidLayoutException {
        mDesc = Preconditions.checkNotNull(familyDesc);

        // Ensure the array of columns is mutable:
        mDesc.setColumns(Lists.newArrayList(mDesc.getColumns()));

        if (!mDesc.getColumns().isEmpty() && (null != mDesc.getMapSchema())) {
          throw new InvalidLayoutException(String.format(
              "Invalid family '%s' with both map-type and columns",
              getName()));
        }

        final Set<String> familyNames = Sets.newHashSet();
        familyNames.add(familyDesc.getName());
        familyNames.addAll(familyDesc.getAliases());
        mNames = ImmutableSet.copyOf(familyNames);

        if (!isValidName(familyDesc.getName())) {
          throw new InvalidLayoutException(String.format(
              "Invalid family name: '%s'.", familyDesc.getName()));
        }

        for (String name : mNames) {
          if (!isValidAlias(name)) {
            throw new InvalidLayoutException(String.format(
                "Invalid family alias: '%s'.", name));
          }
        }

        if (familyDesc.getId() > 0) {
          mId = new ColumnId(familyDesc.getId());
        }

        if (reference != null) {
          if ((mId != null) && !mId.equals(reference.getId())) {
            throw new InvalidLayoutException(String.format(
                "Descriptor for family '%s' has ID %s but reference ID is %s.",
                getName(), mId, reference.getId()));
          }
          mId = reference.getId();
          familyDesc.setId(mId.getId());

          // Cannot change family type (group-type vs map-type):
          if (reference.isMapType() != this.isMapType()) {
            throw new InvalidLayoutException(String.format(
                "Invalid layout update for family '%s' from reference type %s to type %s.",
                getName(),
                reference.isMapType() ? "map" : "group",
                this.isMapType() ? "map" : "group"));
          }
        }

        if (this.isMapType()) {
          // Force validation of schema:
          validateAvroSchema(mDesc.getMapSchema());
        }

        // Build columns:

        /**
         * Map of columns from the reference layout.
         * Entries are removed as they are processed and linked to column descriptors.
         * At the end of the process, this map must be empty.
         */
        final BiMap<String, ColumnId> refCIdMap = (reference != null)
            ? HashBiMap.create(reference.getColumnIdNameMap().inverse())
            : HashBiMap.<String, ColumnId>create();

        final List<ColumnLayout> columns = Lists.newArrayList();
        final Map<String, ColumnLayout> columnMap = Maps.newHashMap();

        /** Map of columns in the new layout. */
        final BiMap<ColumnId, String> idMap = HashBiMap.create();

        /** Columns with no ID assigned yet. */
        final List<ColumnLayout> unassigned = Lists.newArrayList();

        final Iterator<ColumnDesc> itColumnDesc = familyDesc.getColumns().iterator();
        while (itColumnDesc.hasNext()) {
          final ColumnDesc columnDesc = itColumnDesc.next();
          final boolean isRename = (columnDesc.getRenamedFrom() != null);
          final String refCName = isRename ? columnDesc.getRenamedFrom() : columnDesc.getName();
          columnDesc.setRenamedFrom(null);
          if (isRename && (reference == null)) {
            throw new InvalidLayoutException(String.format(
                "Invalid renaming: cannot find reference family for column '%s:%s'.",
                getName(), refCName));
          }
          final ColumnLayout refCLayout =
              (reference != null) ? reference.getColumnMap().get(refCName) : null;
          if (isRename && (refCLayout == null)) {
            throw new InvalidLayoutException(String.format(
                "Invalid renaming: cannot find column '%s:%s' in reference family.",
                getName(), refCName));
          }

          final ColumnId refCId = refCIdMap.remove(refCName);

          if (columnDesc.getDelete()) {
            if (refCId == null) {
              throw new InvalidLayoutException(String.format(
                  "Deleted column '%s:%s' does not exist in reference layout.",
                  mDesc.getName(), refCName));
            }
            itColumnDesc.remove();
            continue;
          }

          final ColumnLayout cLayout = new ColumnLayout(columnDesc, refCLayout);
          columns.add(cLayout);
          for (String columnName : cLayout.getNames()) {
            if (null != columnMap.put(columnName, cLayout)) {
                throw new InvalidLayoutException(String.format(
                    "Family '%s' contains duplicate column qualifier '%s'.",
                    getName(), columnName));
            }
          }
          if (cLayout.getId() != null) {
            final String previous = idMap.put(cLayout.getId(), cLayout.getName());
            Preconditions.checkState(previous == null,
                String.format("Duplicate column ID '%s' associated to '%s' and '%s'.",
                    cLayout.getId(), cLayout.getName(), previous));
          } else {
            unassigned.add(cLayout);
          }
        }

        if (!refCIdMap.isEmpty()) {
          throw new InvalidLayoutException(String.format(
              "Descriptor for family '%s' is missing columns: %s.",
              getName(), Joiner.on(",").join(refCIdMap.keySet())));
        }

        mColumns = ImmutableList.copyOf(columns);
        mColumnMap = ImmutableMap.copyOf(columnMap);

        // Assign IDs to columns, build ID maps
        int nextColumnId = 1;
        for (ColumnLayout column : unassigned) {
          Preconditions.checkState(column.getId() == null);
          while (true) {
            final ColumnId columnId = new ColumnId(nextColumnId);
            nextColumnId += 1;
            if (!idMap.containsKey(columnId)) {
              column.setId(columnId);
              idMap.put(columnId, column.getName());
              break;
            }
          }
        }

        mColumnIdNameMap = ImmutableBiMap.copyOf(idMap);
      }

      /** @return the Avro descriptor for this family. */
      public FamilyDesc getDesc() {
        return mDesc;
      }

      /** @return the primary name for the family. */
      public String getName() {
        return mDesc.getName();
      }

      /** @return the family name and aliases. */
      public Set<String> getNames() {
        return mNames;
      }

      /** @return the column ID assigned to this family. */
      public ColumnId getId() {
        return mId;
      }

      /**
       * Assigns the ID of this family.
       *
       * @param cid the ID of the family.
       * @return this column.
       */
      private FamilyLayout setId(ColumnId cid) {
        Preconditions.checkArgument(cid.getId() >= 1);
        Preconditions.checkState(null == mId);
        mId = cid;
        mDesc.setId(cid.getId());
        return this;
      }

      /** @return the columns in this family. */
      public Collection<ColumnLayout> getColumns() {
        return mColumns;
      }

      /** @return the mapping from column names (no aliases) to column layouts. */
      public Map<String, ColumnLayout> getColumnMap() {
        return mColumnMap;
      }

      /** @return the bidirectional mapping between column names (no aliases) and IDs. */
      public BiMap<ColumnId, String> getColumnIdNameMap() {
        return mColumnIdNameMap;
      }

      /** @return the locality group this family belongs to. */
      public LocalityGroupLayout getLocalityGroup() {
        return LocalityGroupLayout.this;
      }

      /** @return whether this is a group-type family. */
      public boolean isGroupType() {
        return mDesc.getMapSchema() == null;
      }

      /** @return whether this is a map-type family. */
      public boolean isMapType() {
        return !isGroupType();
      }
    }  // class FamilyLayout

    // -------------------------------------------------------------------------------------------

    /** Locality group descriptor. */
    private final LocalityGroupDesc mDesc;

    /** Locality group name and aliases. */
    private final ImmutableSet<String> mNames;

    /** Families in the locality group. */
    private final ImmutableList<FamilyLayout> mFamilies;

    /** Map family name or alias to family layout. */
    private final ImmutableMap<String, FamilyLayout> mFamilyMap;

    /** Bidirectional mapping between family IDs and family names (no alias). */
    private final BiMap<ColumnId, String> mFamilyIdNameBiMap;

    /** Locality group ID. */
    private ColumnId mId = null;

    /**
     * Constructs a locality group layout.
     *
     * @param lgDesc Locality group descriptor.
     * @param reference Optional reference locality group, or null.
     * @throws InvalidLayoutException if the layout is invalid or inconsistent.
     */
    public LocalityGroupLayout(LocalityGroupDesc lgDesc, LocalityGroupLayout reference)
        throws InvalidLayoutException {
      mDesc = Preconditions.checkNotNull(lgDesc);

      // Ensure the array of families is mutable:
      mDesc.setFamilies(Lists.newArrayList(mDesc.getFamilies()));

      // All the recognized names for this locality group:
      final Set<String> names = Sets.newHashSet();
      names.add(lgDesc.getName());
      names.addAll(lgDesc.getAliases());
      mNames = ImmutableSet.copyOf(names);

      if (!isValidName(lgDesc.getName())) {
        throw new InvalidLayoutException(String.format(
            "Invalid locality group name: '%s'.", lgDesc.getName()));
      }

      for (String name : mNames) {
        if (!isValidAlias(name)) {
          throw new InvalidLayoutException(String.format(
              "Invalid locality group alias: '%s'.", name));
        }
      }

      if (lgDesc.getId() > 0) {
        mId = new ColumnId(lgDesc.getId());
      }

      if (mDesc.getTtlSeconds() <= 0) {
        throw new InvalidLayoutException(String.format(
            "Invalid TTL seconds for locality group '%s': TTL must be positive, got %d.",
            getName(), mDesc.getTtlSeconds()));
      }
      if (mDesc.getMaxVersions() <= 0) {
        throw new InvalidLayoutException(String.format(
            "Invalid max versions for locality group '%s': max versions must be positive, got %d.",
            getName(), mDesc.getMaxVersions()));
      }

      if (reference != null) {
        if ((mId != null) && !mId.equals(reference.getId())) {
          throw new InvalidLayoutException(String.format(
              "Descriptor for locality group '%s' has ID %s but reference ID is %s.",
              getName(), mId, reference.getId()));
        }
        mId = reference.getId();
        lgDesc.setId(mId.getId());
      }

      // Build families:

      /**
       * Map of the family IDs from the reference layout.
       * Entries are removed as they are linked to the families in the descriptor.
       * Eventually, this map must become empty.
       */
      final BiMap<String, ColumnId> refFIdMap = (reference != null)
          ? HashBiMap.create(reference.getFamilyIdNameMap().inverse())
          : HashBiMap.<String, ColumnId>create();

      final List<FamilyLayout> families = Lists.newArrayList();
      final Map<String, FamilyLayout> familyMap = Maps.newHashMap();

      /** Map of families in the new layout. */
      final BiMap<ColumnId, String> idMap = HashBiMap.create();

      /** Families with no ID assigned yet. */
      final List<FamilyLayout> unassigned = Lists.newArrayList();

      final Iterator<FamilyDesc> itFamilyDesc = lgDesc.getFamilies().iterator();
      while (itFamilyDesc.hasNext()) {
        final FamilyDesc familyDesc = itFamilyDesc.next();
        final boolean isRename = (familyDesc.getRenamedFrom() != null);
        final String refFName = isRename ? familyDesc.getRenamedFrom() : familyDesc.getName();
        familyDesc.setRenamedFrom(null);
        if (isRename && (reference == null)) {
          throw new InvalidLayoutException(String.format(
              "Invalid rename: no reference locality group '%s' for family '%s'.",
              getName(), refFName));
        }
        final FamilyLayout refFLayout =
            (reference != null) ? reference.getFamilyMap().get(refFName) : null;
        if (isRename && (refFLayout == null)) {
          throw new InvalidLayoutException(String.format(
              "Invalid rename: cannot find reference family '%s' in locality group '%s'.",
              refFName, getName()));
        }

        final ColumnId refFId = refFIdMap.remove(refFName);

        if (familyDesc.getDelete()) {
          if (refFId == null) {
            throw new InvalidLayoutException(String.format(
                "Deleted family '%s' unknown in reference locality group '%s'.",
                refFName, getName()));
          }
          itFamilyDesc.remove();
          continue;
        }

        final FamilyLayout fLayout = new FamilyLayout(familyDesc, refFLayout);
        families.add(fLayout);
        for (String familyName : fLayout.getNames()) {
          Preconditions.checkState(familyMap.put(familyName, fLayout) == null,
              "Duplicate family name: " + familyName);
        }
        if (fLayout.getId() != null) {
          final String previous = idMap.put(fLayout.getId(), fLayout.getName());
          Preconditions.checkState(previous == null,
              String.format("Duplicate family ID '%s' associated to '%s' and '%s'.",
                  fLayout.getId(), fLayout.getName(), previous));
        } else {
          unassigned.add(fLayout);
        }
      }

      if (!refFIdMap.isEmpty()) {
        throw new InvalidLayoutException(String.format(
            "Descriptor for locality group '%s' is missing families: %s",
            lgDesc.getName(), Joiner.on(",").join(refFIdMap.keySet())));
      }

      mFamilies = ImmutableList.copyOf(families);
      mFamilyMap = ImmutableMap.copyOf(familyMap);

      // Assign IDs to families:
      int nextFamilyId = 1;
      for (FamilyLayout fLayout : unassigned) {
        Preconditions.checkState(fLayout.getId() == null);
        while (true) {
          final ColumnId fId = new ColumnId(nextFamilyId);
          nextFamilyId += 1;
          if (!idMap.containsKey(fId)) {
            fLayout.setId(fId);
            idMap.put(fId, fLayout.getName());
            break;
          }
        }
      }

      mFamilyIdNameBiMap = ImmutableBiMap.copyOf(idMap);
    }

    /** @return the table layout this locality group belongs to. */
    public KijiTableLayout getTableLayout() {
      return KijiTableLayout.this;
    }

    /** @return the Avro descriptor for this locality group. */
    public LocalityGroupDesc getDesc() {
      return mDesc;
    }

    /** @return the locality group primary name. */
    public String getName() {
      return mDesc.getName();
    }

    /** @return the locality group name and aliases. */
    public Set<String> getNames() {
      return mNames;
    }

    /** @return the ID associated to this locality group. */
    public ColumnId getId() {
      return mId;
    }

    /**
     * Assigns the ID of the locality group.
     *
     * @param cid the ID of the locality group.
     * @return this locality group.
     */
    private LocalityGroupLayout setId(ColumnId cid) {
      Preconditions.checkArgument(cid.getId() >= 1);
      Preconditions.checkState(null == mId);
      mId = cid;
      mDesc.setId(cid.getId());
      return this;
    }

    /** @return the families in this locality group, in no particular order. */
    public Collection<FamilyLayout> getFamilies() {
      return mFamilies;
    }

    /** @return the mapping from family names and aliases to family layouts. */
    public Map<String, FamilyLayout> getFamilyMap() {
      return mFamilyMap;
    }

    /** @return the bidirectional mapping between family names (no alias) and IDs. */
    public BiMap<ColumnId, String> getFamilyIdNameMap() {
      return mFamilyIdNameBiMap;
    }

  }  // class LocalityGroupLayout

  // -----------------------------------------------------------------------------------------------

  /** Avro record describing the table layout absolutely (no reference layout required). */
  private final TableLayoutDesc mDesc;

  /** Locality groups in the table, in no particular order. */
  private /*final*/ ImmutableList<LocalityGroupLayout> mLocalityGroups;

  /** Map locality group name or alias to locality group layout. */
  private /*final*/ ImmutableMap<String, LocalityGroupLayout> mLocalityGroupMap;

  /** Families in the table, in no particular order. */
  private /*final*/ ImmutableList<FamilyLayout> mFamilies;

  /** Map family names and aliases to family layout. */
  private /*final*/ ImmutableMap<String, FamilyLayout> mFamilyMap;

  /** Bidirectional map between locality group names (no alias) and IDs. */
  private /*final*/ ImmutableBiMap<ColumnId, String> mLocalityGroupIdNameMap;

  /** All primary column names in the table (including names for map-type families). */
  private /*final*/ ImmutableSet<KijiColumnName> mColumnNames;

  // CSOFF: MethodLengthCheck
  /**
   * Constructs a KijiTableLayout from an Avro descriptor and an optional reference layout.
   *
   * @param desc Avro layout descriptor (relative to the reference layout).
   * @param reference Optional reference layout, or null.
   * @throws InvalidLayoutException if the descriptor is invalid or inconsistent wrt reference.
   */
  public KijiTableLayout(TableLayoutDesc desc, KijiTableLayout reference)
      throws InvalidLayoutException {
    // Deep-copy the descriptor to prevent mutating a parameter:
    mDesc = TableLayoutDesc.newBuilder(Preconditions.checkNotNull(desc)).build();

    // Ensure the array of locality groups is mutable:
    mDesc.setLocalityGroups(Lists.newArrayList(mDesc.getLocalityGroups()));

    if (!isValidName(getName())) {
      throw new InvalidLayoutException(String.format("Invalid table name: '%s'.", getName()));
    }

    if (reference != null) {
      if (!getName().equals(reference.getName())) {
        throw new InvalidLayoutException(String.format(
            "Invalid layout update: layout name '%s' does not match reference layout name '%s'.",
            getName(), reference.getName()));
      }

      if (!mDesc.getKeysFormat().equals(reference.getDesc().getKeysFormat())) {
        throw new InvalidLayoutException(String.format(
            "Invalid layout update from reference row keys format '%s' to row keys format '%s'.",
            reference.getDesc().getKeysFormat(), mDesc.getKeysFormat()));
      }
    }

    // Layout ID:
    if (mDesc.getLayoutId() == null) {
      try {
        final long refLayoutId =
            (reference == null) ? 0 : Long.parseLong(reference.getDesc().getLayoutId());
        final long layoutId = refLayoutId + 1;
        mDesc.setLayoutId(Long.toString(layoutId));
      } catch (NumberFormatException nfe) {
        throw new InvalidLayoutException(String.format(
            "Reference layout for table '%s' has an invalid layout ID: '%s'",
            getName(), reference.getDesc().getLayoutId()));
      }
    }

    // Build localities:

    /**
     * Reference map from locality group name to locality group ID.
     * Entries are removed as we process locality group descriptors in the new layout.
     * At the end of the process, this map must be empty.
     */
    final BiMap<String, ColumnId> refLGIdMap = (reference == null)
        ? HashBiMap.<String, ColumnId>create()
        : HashBiMap.create(reference.mLocalityGroupIdNameMap.inverse());

    /** Map of locality groups in the new layout. */
    final List<LocalityGroupLayout> localityGroups = Lists.newArrayList();
    final Map<String, LocalityGroupLayout> lgMap = Maps.newHashMap();
    final BiMap<ColumnId, String> idMap = HashBiMap.create();

    /** Locality group with no ID assigned yet. */
    final List<LocalityGroupLayout> unassigned = Lists.newArrayList();

    /** All the families in the table. */
    final List<FamilyLayout> families = Lists.newArrayList();

    /** Map from family name or alias to family layout. */
    final Map<String, FamilyLayout> familyMap = Maps.newHashMap();

    /** All primary column names (including map-type families). */
    final Set<KijiColumnName> columnNames = Sets.newTreeSet();

    final Map<KijiColumnName, ColumnLayout> columnMap = Maps.newHashMap();

    final Iterator<LocalityGroupDesc> itLGDesc = mDesc.getLocalityGroups().iterator();
    while (itLGDesc.hasNext()) {
      final LocalityGroupDesc lgDesc = itLGDesc.next();
      final boolean isRename = (lgDesc.getRenamedFrom() != null);
      final String refLGName = isRename ? lgDesc.getRenamedFrom() : lgDesc.getName();
      lgDesc.setRenamedFrom(null);
      if (isRename && (reference == null)) {
        throw new InvalidLayoutException(String.format(
            "Invalid rename: no reference table layout for locality group '%s'.", refLGName));
      }
      final LocalityGroupLayout refLGLayout =
          (reference != null) ? reference.mLocalityGroupMap.get(refLGName) : null;
      if (isRename && (refLGLayout == null)) {
        throw new InvalidLayoutException(String.format(
            "Invalid rename: cannot find reference locality group '%s'.", refLGName));
      }

      final ColumnId refLGId = refLGIdMap.remove(refLGName);

      if (lgDesc.getDelete()) {
        // This locality group is deleted:
        if (refLGId == null) {
          throw new InvalidLayoutException(String.format(
              "Attempting to delete locality group '%s' unknown in reference layout.",
              refLGName));
        }
        itLGDesc.remove();
        continue;
      }

      final LocalityGroupLayout lgLayout = new LocalityGroupLayout(lgDesc, refLGLayout);
      localityGroups.add(lgLayout);
      for (String lgName : lgLayout.getNames()) {
        Preconditions.checkState(lgMap.put(lgName, lgLayout) == null,
            "Duplicate locality group name: " + lgName);
      }

      if (lgLayout.getId() != null) {
        final String previous = idMap.put(lgLayout.getId(), lgLayout.getName());
        Preconditions.checkState(previous == null,
            String.format("Duplicate locality group ID '%s' associated to '%s' and '%s'.",
                lgLayout.getId(), lgLayout.getName(), previous));
      } else {
        unassigned.add(lgLayout);
      }

      families.addAll(lgLayout.getFamilies());
      for (FamilyLayout familyLayout : lgLayout.getFamilies()) {
        for (String familyName : familyLayout.getNames()) {
          if (null != familyMap.put(familyName, familyLayout)) {
              throw new InvalidLayoutException(String.format(
                  "Layout for table '%s' contains duplicate family name '%s'.",
                  getName(), familyName));
          }
        }

        if (familyLayout.isMapType()) {
          Preconditions.checkState(
              columnNames.add(new KijiColumnName(familyLayout.getName(), null)));
        }

        for (ColumnLayout columnLayout: familyLayout.getColumns()) {
          for (String columnName : columnLayout.getNames()) {
            final KijiColumnName column = new KijiColumnName(familyLayout.getName(), columnName);
            if (null != columnMap.put(column, columnLayout)) {
              throw new InvalidLayoutException(String.format(
                  "Layout for table '%s' contains duplicate column '%s'.",
                  getName(), column));
            }
          }
          Preconditions.checkState(
              columnNames.add(new KijiColumnName(familyLayout.getName(), columnLayout.getName())));
        }
      }
    }

    if (!refLGIdMap.isEmpty()) {
      throw new InvalidLayoutException(String.format(
          "Missing descriptor(s) for locality group(s): %s.",
          Joiner.on(",").join(refLGIdMap.keySet())));
    }

    mLocalityGroups = ImmutableList.copyOf(localityGroups);
    mLocalityGroupMap = ImmutableMap.copyOf(lgMap);

    mFamilies = ImmutableList.copyOf(families);
    mFamilyMap = ImmutableMap.copyOf(familyMap);

    mColumnNames = ImmutableSet.copyOf(columnNames);

    // Assign IDs to locality groups:
    int nextColumnId = 1;
    for (LocalityGroupLayout localityGroup : unassigned) {
      Preconditions.checkState(localityGroup.getId() == null);
      while (true) {
        final ColumnId columnId = new ColumnId(nextColumnId);
        nextColumnId += 1;
        if (!idMap.containsKey(columnId)) {
          localityGroup.setId(columnId);
          idMap.put(columnId, localityGroup.getName());
          break;
        }
      }
    }

    mLocalityGroupIdNameMap = ImmutableBiMap.copyOf(idMap);
  }
  // CSON: MethodLengthCheck

  /** @return the Avro descriptor for this table layout. */
  public TableLayoutDesc getDesc() {
    return mDesc;
  }

  /** @return the table name. */
  public String getName() {
    return mDesc.getName();
  }

  /** @return the locality groups in the table, in no particular order. */
  public Collection<LocalityGroupLayout> getLocalityGroups() {
    return mLocalityGroups;
  }

  /** @return the bidirectional mapping between locality group names (no alias) and IDs. */
  public BiMap<ColumnId, String> getLocalityGroupIdNameMap() {
    return mLocalityGroupIdNameMap;
  }

  /** @return the map from locality group names and aliases to layouts. */
  public Map<String, LocalityGroupLayout> getLocalityGroupMap() {
    return mLocalityGroupMap;
  }

  /** @return the mapping from family names and aliases to family layouts in the table. */
  public Map<String, FamilyLayout> getFamilyMap() {
    return mFamilyMap;
  }

  /** @return all the families in the table, in no particular order. */
  public Collection<FamilyLayout> getFamilies() {
    return mFamilies;
  }

  /** @return all the primary column names in the table, including map-type families. */
  public Set<KijiColumnName> getColumnNames() {
    return mColumnNames;
  }

  /**
   * Gets the schema for a column.
   *
   * @param columnName The name of the column to get the schema for.
   * @return the schema of the specified column.
   * @throws NoSuchColumnException if the column does not exist.
   */
  public CellSchema getCellSchema(KijiColumnName columnName) throws NoSuchColumnException {
    final FamilyLayout fLayout = mFamilyMap.get(columnName.getFamily());
    if (fLayout == null) {
      throw new NoSuchColumnException(String.format(
          "Table '%s' has no family '%s'.", getName(), columnName.getFamily()));
    }
    if (fLayout.isMapType()) {
      return fLayout.getDesc().getMapSchema();
    }

    // Group-type family:
    Preconditions.checkArgument(columnName.isFullyQualified(),
        String.format("Cannot get CellFormat for entire group-type family: '%s'.", columnName));
    final FamilyLayout.ColumnLayout cLayout =
        fLayout.getColumnMap().get(columnName.getQualifier());
    if (cLayout == null) {
      throw new NoSuchColumnException(String.format(
          "Table '%s' has no column '%s'.", getName(), columnName));
    }
    return cLayout.getDesc().getColumnSchema();
  }

  /**
   * Reports the schema of the specified column.
   *
   * @param columnName Column name.
   * @return the schema of the column.
   * @throws InvalidLayoutException if the layout is invalid.
   * @throws NoSuchColumnException if the column does not exist.
   */
  public Schema getSchema(KijiColumnName columnName)
      throws InvalidLayoutException, NoSuchColumnException {
    return readAvroSchema(getCellSchema(columnName));
  }

  /**
   * Reports the cell format for the specified column.
   *
   * @param column Column name.
   * @return the cell format for the column.
   * @throws NoSuchColumnException if the column does not exist.
   */
  public KijiCellFormat getCellFormat(KijiColumnName column) throws NoSuchColumnException {
    return KijiCellFormat.fromSchemaStorage(getCellSchema(column).getStorage());
  }

  /**
   * Reports whether a column exists.
   *
   * @param column Column name.
   * @return whether the specified column exists.
   */
  public boolean exists(KijiColumnName column) {
    final FamilyLayout fLayout = mFamilyMap.get(column.getFamily());
    if (fLayout == null) {
      // Family does not exist:
      return false;
    }

    if (fLayout.isMapType()) {
      // This is a map-type family, we don't need to validate the qualifier:
      return true;
    }

    // This is a group-type family:
    if (!column.isFullyQualified()) {
      // No column qualifier, the group-type family exists:
      return true;
    }

    // Validate the qualifier:
    return fLayout.getColumnMap().containsKey(column.getQualifier());
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(Object other) {
    if (!(other instanceof KijiTableLayout)) {
      return false;
    }
    final KijiTableLayout otherLayout = (KijiTableLayout) other;
    return getDesc().equals(otherLayout.getDesc());
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return getDesc().hashCode();
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    try {
      return ToJson.toJsonString(mDesc);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Validates a name (table name, locality group name, family name, or column name).
   *
   * @param name The name to validate.
   * @return whether the name is valid.
   */
  private static boolean isValidName(String name) {
    return KijiNameValidator.isValidLayoutName(name);
  }

  /**
   * Validates an alias (table name, locality group name, family name, or column name).
   *
   * @param alias The alias to validate.
   * @return whether the alias is valid.
   */
  private static boolean isValidAlias(String alias) {
    return KijiNameValidator.isValidAlias(alias);
  }

  /**
   * Reads the Avro schema from the table layout.
   *
   * @param avro The portion of the table layout record to read from.
   * @return The avro schema, or null if the schema is type "counter".
   * @throws InvalidLayoutException if the table layout is invalid and the schema can't be read.
   */
  public static Schema readAvroSchema(CellSchema avro) throws InvalidLayoutException {
    switch (avro.getType()) {
    case INLINE:
      try {
        return new Schema.Parser().parse(avro.getValue());
      } catch (RuntimeException e) {
        throw new InvalidLayoutException("Invalid schema: " + e.getMessage());
      }
    case CLASS:
      String className = avro.getValue();
      if (!JavaIdentifiers.isValidClassName(className)) {
        throw new InvalidLayoutException(
            "Schema with type 'class' must be a valid Java identifier.");
      }
      try {
        Class<?> avroClass = Class.forName(className);
        try {
          return SpecificData.get().getSchema(avroClass);
        } catch (RuntimeException e) {
          throw new InvalidLayoutException(
              "Java class is not a valid Avro type: " + avroClass.getName());
        }
      } catch (ClassNotFoundException e) {
        throw new SchemaClassNotFoundException(
            "Java class " + avro.getValue() + " was not found on the classpath.");
      }
    case COUNTER:
      // Counters are coded using a Bytes.toBytes(long) and Bytes.toLong(byte[]):
      return null;
    default:
      throw new InvalidLayoutException("Invalid schema type: " + avro.getType());
    }
  }

  /**
   * Validates a cell schema descriptor.
   *
   * Ignores failures due to specific Avro record classes not being present on the claspath.
   *
   * @param avro Cell schema descriptor.
   * @throws InvalidLayoutException if the cell schema descriptor is invalid.
   */
  private static void validateAvroSchema(CellSchema avro) throws InvalidLayoutException {
    try {
      readAvroSchema(avro);
    } catch (SchemaClassNotFoundException scnfe) {
      LOG.debug(String.format("Avro schema class '%s' not found.", avro.getValue()));
    }
  }

  /**
   * Loads a table layout from the specified resource as JSON.
   *
   * @param resource Path of the resource containing the JSON layout description.
   * @return the parsed table layout.
   * @throws IOException on I/O error.
   */
  public static KijiTableLayout createFromEffectiveJsonResource(String resource)
      throws IOException {
    return createFromEffectiveJson(KijiTableLayout.class.getResourceAsStream(resource));
  }

  /**
   * Loads a table layout from the specified JSON text.
   *
   * @param istream Input stream containing the JSON text.
   * @return the parsed table layout.
   * @throws IOException on I/O error.
   */
  public static KijiTableLayout createFromEffectiveJson(InputStream istream) throws IOException {
    try {
      final TableLayoutDesc desc = readTableLayoutDescFromJSON(istream);
      final KijiTableLayout layout = new KijiTableLayout(desc, null);
      return layout;
    } finally {
      IOUtils.closeQuietly(istream);
    }
  }

  /**
   * Reads a table layout descriptor from its JSON serialized form.
   *
   * @param istream JSON input stream.
   * @return the decoded table layout descriptor.
   * @throws IOException on I/O error.
   */
  public static TableLayoutDesc readTableLayoutDescFromJSON(InputStream istream)
      throws IOException {
    final String json = IOUtils.toString(istream);
    final TableLayoutDesc desc =
        (TableLayoutDesc) FromJson.fromJsonString(json, TableLayoutDesc.SCHEMA$);
    return desc;
  }

}