/* Copyright (c) 2001-2022, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb.rights;

import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.NumberSequence;
import org.hsqldb.Routine;
import org.hsqldb.SchemaObject;
import org.hsqldb.Session;
import org.hsqldb.Table;
import org.hsqldb.Tokens;
import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.lib.HashMap;
import org.hsqldb.lib.HsqlArrayList;
import org.hsqldb.lib.Iterator;
import org.hsqldb.lib.MultiValueHashMap;
import org.hsqldb.lib.OrderedHashSet;
import org.hsqldb.types.Type;

/**
 * A Grantee Object holds the name, access and administrative rights for a
 * particular grantee.<p>
 * It supplies the methods used to grant, revoke, test
 * and check a grantee's access rights to other database objects.
 * It also holds a reference to the common PUBLIC User Object,
 * which represent the special user referred to in
 * GRANT ... TO PUBLIC statements.<p>
 * The check(), isAccessible() and getGrantedClassNames() methods check the
 * rights granted to the PUBLIC User Object, in addition to individually
 * granted rights, in order to decide which rights exist for the user.
 *
 * Method names ending in Direct indicate methods which do not recurse
 * to look through Roles which "this" object is a member of.
 *
 * We use the word "Admin" (e.g., in private variable "admin" and method
 * "isAdmin()) to mean this Grantee has admin priv by any means.
 * We use the word "adminDirect" (e.g., in private variable "adminDirect"
 * and method "isAdminDirect()) to mean this Grantee has admin priv
 * directly.
 *
 * @author Campbell Burnet (campbell-burnet@users dot sourceforge.net)
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @author Blaine Simpson (blaine dot simpson at admc dot com)
 *
 * @version 2.7.0
 * @since 1.8.0
 */
public class Grantee implements SchemaObject {

    boolean isRole;

    /**
     * true if this grantee has database administrator priv directly
     *  (ie., not by membership in any role)
     */
    private boolean isAdminDirect = false;

    /** true if this grantee has database administrator priv by any means. */
    private boolean isAdmin = false;

    /** true if this grantee is PUBLIC. */
    boolean isPublic = false;

    /** true if this grantee is _SYSTEM. */
    boolean isSystem = false;

    /** Grantee name. */
    protected HsqlName granteeName;

    /** map with database object identifier keys and access privileges values */
    private MultiValueHashMap directRightsMap;

    /** contains rights granted direct, or via roles, except those of PUBLIC */
    HashMap fullRightsMap;

    /** These are the DIRECT roles.  Each of these may contain nested roles */
    OrderedHashSet roles;

    /** map with database object identifier keys and access privileges values */
    private MultiValueHashMap grantedRightsMap;

    /** Needed only to give access to the roles for this database */
    protected GranteeManager granteeManager;

    /**  */
    protected Right ownerRights;

    /**
     * Constructor.
     */
    Grantee(HsqlName name, GranteeManager man) {

        fullRightsMap       = new HashMap();
        directRightsMap     = new MultiValueHashMap();
        grantedRightsMap    = new MultiValueHashMap();
        granteeName         = name;
        granteeManager      = man;
        roles               = new OrderedHashSet();
        ownerRights         = new Right();
        ownerRights.isFull  = true;
        ownerRights.grantor = GranteeManager.systemAuthorisation;
        ownerRights.grantee = this;
    }

    public int getType() {
        return SchemaObject.GRANTEE;
    }

    public HsqlName getName() {
        return granteeName;
    }

    public HsqlName getSchemaName() {
        return null;
    }

    public HsqlName getCatalogName() {
        return null;
    }

    public Grantee getOwner() {
        return null;
    }

    public OrderedHashSet getReferences() {
        return new OrderedHashSet();
    }

    public OrderedHashSet getComponents() {
        return null;
    }

    public void compile(Session session, SchemaObject parentObject) {}

    public String getSQL() {

        StringBuilder sb = new StringBuilder();

        sb.append(Tokens.T_CREATE).append(' ').append(Tokens.T_ROLE);
        sb.append(' ').append(granteeName.statementName);

        return sb.toString();
    }

    public long getChangeTimestamp() {
        return 0;
    }

    public boolean isRole() {
        return isRole;
    }

    public boolean isSystem() {
        return isSystem;
    }

    /**
     * Gets direct roles, not roles nested within them.
     */
    public OrderedHashSet getDirectRoles() {
        return roles;
    }

    /**
     * Gets direct and indirect roles.
     */
    public OrderedHashSet getAllRoles() {

        OrderedHashSet set = getGranteeAndAllRoles();

        // Since we added "Grantee" in addition to Roles, need to remove self.
        set.remove(this);

        return set;
    }

    public OrderedHashSet getGranteeAndAllRoles() {

        OrderedHashSet set = new OrderedHashSet();

        addGranteeAndRoles(set);

        return set;
    }

    public OrderedHashSet getGranteeAndAllRolesWithPublic() {

        OrderedHashSet set = new OrderedHashSet();

        addGranteeAndRoles(set);
        set.add(granteeManager.publicRole);

        return set;
    }

    public boolean isAccessible(HsqlName name, int action) {

        if (isFullyAccessibleByRole(name)) {
            return true;
        }

        Right right = (Right) fullRightsMap.get(name);

        if (right == null) {
            return false;
        }

        return right.canAccess(action);
    }

    /**
     * returns true if grantee has any privilege (to any column) of the object
     */
    public boolean isAccessible(SchemaObject object) {
        return isAccessible(object.getName());
    }

    public boolean isAccessible(HsqlName name) {

        if (isFullyAccessibleByRole(name)) {
            return true;
        }

        Right right = (Right) fullRightsMap.get(name);

        if (right != null && !right.isEmpty()) {
            return true;
        }

        if (!isPublic) {
            return granteeManager.publicRole.isAccessible(name);
        }

        return false;
    }

    /**
     * Adds to given Set this.sName plus all roles and nested roles.
     *
     * @return Given role with new elements added.
     */
    private OrderedHashSet addGranteeAndRoles(OrderedHashSet set) {

        Grantee candidateRole;

        set.add(this);

        for (int i = 0; i < roles.size(); i++) {
            candidateRole = (Grantee) roles.get(i);

            if (!set.contains(candidateRole)) {
                candidateRole.addGranteeAndRoles(set);
            }
        }

        return set;
    }

    private boolean hasRoleDirect(Grantee role) {
        return roles.contains(role);
    }

    public boolean hasRole(Grantee role) {
        return getAllRoles().contains(role);
    }

    private void grantToAll(HsqlName name, Right right, Grantee grantor,
                            boolean withGrant) {

        int objectType = SchemaObject.TABLE;

        if (right.isFullUsage) {
            right      = Right.fullRights;
            objectType = SchemaObject.SEQUENCE;
        } else if (right.isFullExecute) {
            right      = Right.fullRights;
            objectType = SchemaObject.SPECIFIC_ROUTINE;
        }

        Iterator it =
            granteeManager.database.schemaManager.databaseObjectIterator(
                name.name, objectType);

        while (it.hasNext()) {
            SchemaObject object = (SchemaObject) it.next();

            grant(object.getName(), right, grantor, withGrant);
        }
    }

    /**
     * Grants the specified rights on the specified database object. <p>
     *
     * Keys stored in rightsMap for database tables are their HsqlName
     * attribute. This allows rights to persist when a table is renamed.
     */
    void grant(HsqlName name, Right right, Grantee grantor,
               boolean withGrant) {

        if (name.type == SchemaObject.SCHEMA) {
            grantToAll(name, right, grantor, withGrant);

            return;
        }

        final Right grantableRights = grantor.getAllGrantableRights(name);
        Right       existingRight   = null;

        if (right == Right.fullRights) {
            if (grantableRights.isEmpty()) {
                return;    // has no rights
            }

            right = grantableRights;
        } else {
            if (!grantableRights.contains(right)) {
                throw Error.error(ErrorCode.X_0L000);
            }
        }

        Iterator it = directRightsMap.getValuesIterator(name);

        while (it.hasNext()) {
            Right existing = (Right) it.next();

            if (existing.grantor == grantor) {
                existingRight = existing;

                existingRight.add(right);

                break;
            }
        }

        if (existingRight == null) {
            existingRight         = right.duplicate();
            existingRight.grantor = grantor;
            existingRight.grantee = this;

            directRightsMap.put(name, existingRight);
        }

        if (withGrant) {
            if (existingRight.grantableRights == null) {
                existingRight.grantableRights = right.duplicate();
            } else {
                existingRight.grantableRights.add(right);
            }
        }

        if (!grantor.isSystem()) {

            // based on assumption that there is no need to access
            grantor.grantedRightsMap.put(name, existingRight);
        }

        updateAllRights();
    }

    private void revokeFromAll(HsqlName name, Right right, Grantee grantor,
                               boolean grantOption) {

        int objectType = SchemaObject.TABLE;

        if (right.isFullUsage) {
            right      = Right.fullRights;
            objectType = SchemaObject.SEQUENCE;
        } else if (right.isFullExecute) {
            right      = Right.fullRights;
            objectType = SchemaObject.SPECIFIC_ROUTINE;
        }

        Iterator it =
            granteeManager.database.schemaManager.databaseObjectIterator(
                name.name, objectType);

        while (it.hasNext()) {
            SchemaObject object = (SchemaObject) it.next();

            revoke(object, right, grantor, grantOption);
        }
    }

    /**
     * Revokes the specified rights on the specified database object. <p>
     *
     * If, after removing the specified rights, no rights remain on the
     * database object, then the key/value pair for that object is removed
     * from the rights map
     */
    void revoke(SchemaObject object, Right right, Grantee grantor,
                boolean grantOption) {

        HsqlName name = object.getName();

        if (name.type == SchemaObject.SCHEMA) {
            grantToAll(name, right, grantor, grantOption);

            return;
        }

        if (object instanceof Routine) {
            name = ((Routine) object).getSpecificName();
        }

        Iterator it       = directRightsMap.getValuesIterator(name);
        Right    existing = null;

        while (it.hasNext()) {
            existing = (Right) it.next();

            if (existing.grantor == grantor) {
                break;
            }
        }

        if (existing == null) {
            return;
        }

        if (existing.grantableRights != null) {
            existing.grantableRights.remove(object, right);
        }

        if (grantOption) {
            return;
        }

        if (right.isFull) {
            directRightsMap.remove(name, existing);
            grantor.grantedRightsMap.remove(name, existing);
            updateAllRights();

            return;
        }

        existing.remove(object, right);

        if (existing.isEmpty()) {
            directRightsMap.remove(name, existing);
            grantor.grantedRightsMap.remove(name, existing);
        }

        updateAllRights();
    }

    /**
     * Revokes all rights on the specified database object.<p>
     *
     * This method removes any existing mapping from the rights map
     */
    void revokeDbObject(HsqlName name) {

        directRightsMap.remove(name);
        grantedRightsMap.remove(name);
        fullRightsMap.remove(name);
    }

    /**
     * Update own table column set rights to include a newly created column.<p?
     */
    void updateRightsForNewColumn(HsqlName tableName, HsqlName columnName) {

        Iterator it       = directRightsMap.getValuesIterator(tableName);
        Right    existing = null;

        while (it.hasNext()) {
            existing = (Right) it.next();
        }

        if (existing == null) {
            return;
        }

        existing.addNewColumn(columnName);
        updateAllRights();
    }

    /**
     * Update granted rights to include a newly created column.<p?
     */
    void updateRightsForNewColumn(HsqlName tableName) {

        Iterator it       = grantedRightsMap.getValuesIterator(tableName);
        Right    existing = null;

        while (it.hasNext()) {
            existing = (Right) it.next();
        }

        if (existing == null) {
            return;
        }

        updateAllRights();
    }

    /**
     * Revokes all rights from this Grantee object.  The map is cleared and
     * the database administrator role attribute is set false.
     */
    void clearPrivileges() {

        roles.clear();
        directRightsMap.clear();
        grantedRightsMap.clear();
        fullRightsMap.clear();

        isAdmin = false;
    }

    public OrderedHashSet getColumnsForAllPrivileges(SchemaObject object) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return table.getColumnNameSet();
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            return right == null ? Right.emptySet
                                 : right.getColumnsForAllRights(table);
        }

        return Right.emptySet;
    }

    public OrderedHashSet getAllDirectPrivileges(SchemaObject object) {

        if (object.getOwner() == this) {
            OrderedHashSet set = new OrderedHashSet();

            set.add(ownerRights);

            return set;
        }

        HsqlName name = object.getName();

        if (object instanceof Routine) {
            name = ((Routine) object).getSpecificName();
        }

        Iterator rights = directRightsMap.getValuesIterator(name);

        if (rights.hasNext()) {
            OrderedHashSet set = new OrderedHashSet();

            while (rights.hasNext()) {
                set.add(rights.next());
            }

            return set;
        }

        return Right.emptySet;
    }

    public OrderedHashSet getAllGrantedPrivileges(SchemaObject object) {

        HsqlName name = object.getName();

        if (object instanceof Routine) {
            name = ((Routine) object).getSpecificName();
        }

        Iterator rights = grantedRightsMap.getValuesIterator(name);

        if (rights.hasNext()) {
            OrderedHashSet set = new OrderedHashSet();

            while (rights.hasNext()) {
                set.add(rights.next());
            }

            return set;
        }

        return Right.emptySet;
    }

    /**
     * Checks if a right represented by the methods
     * have been granted on the specified database object. <p>
     *
     * This is done by checking that a mapping exists in the rights map
     * from the dbobject argument. Otherwise, it throws.
     */
    public Right checkSelect(SchemaObject object, boolean[] checkList) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canSelect(table, checkList)) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkInsert(SchemaObject object, boolean[] checkList) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canInsert(table, checkList)) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkUpdate(SchemaObject object, boolean[] checkList) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canUpdate(table, checkList)) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkReferences(SchemaObject object, boolean[] checkList) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canReference(table, checkList)) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkTrigger(SchemaObject object, boolean[] checkList) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canReference(table, checkList)) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkDelete(SchemaObject object) {

        if (object instanceof Table) {
            Table table = (Table) object;

            if (isFullyAccessibleByRole(table.getName())) {
                return Right.fullRights;
            }

            Right right = (Right) fullRightsMap.get(table.getName());

            if (right != null && right.canDelete()) {
                return right;
            }
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    public Right checkAccess(SchemaObject object) {

        if (isFullyAccessibleByRole(object.getName())) {
            return Right.fullRights;
        }

        HsqlName name = object.getName();

        if (object instanceof Routine) {
            name = ((Routine) object).getSpecificName();
        }

        Right right = (Right) fullRightsMap.get(name);

        if (right != null && !right.isEmpty()) {
            return right;
        }

        throw Error.error(ErrorCode.X_42501, object.getName().name);
    }

    /**
     * Checks if this object can modify schema objects or grant access rights
     * to them.
     */
    public void checkSchemaUpdateOrGrantRights(HsqlName schemaName) {

        if (!hasSchemaUpdateOrGrantRights(schemaName)) {
            throw Error.error(ErrorCode.X_42501, schemaName.name);
        }
    }

    /**
     * Checks if this object can modify schema objects or grant access rights
     * to them.
     */
    public boolean hasSchemaUpdateOrGrantRights(HsqlName schemaName) {

        // If a DBA
        if (isAdmin()) {
            return true;
        }

        Grantee schemaOwner =
            granteeManager.database.schemaManager.toSchemaOwner(
                schemaName.name);

        // If owner of Schema
        if (schemaOwner == this) {
            return true;
        }

        // If a member of Schema authorization role
        if (hasRole(schemaOwner)) {
            return true;
        }

        return false;
    }

    public boolean isGrantable(SchemaObject object, Right right) {

        if (isFullyAccessibleByRole(object.getName())) {
            return true;
        }

        Right grantableRights = getAllGrantableRights(object.getName());

        return grantableRights.contains(right);
    }

    public boolean isGrantable(Grantee role) {
        return isAdmin;
    }

    public boolean isFullyAccessibleByRole(HsqlName name) {

        Grantee owner;

        if (isAdmin) {
            return true;
        }

        if (name.type == SchemaObject.SCHEMA) {
            owner = name.owner;
        } else if (name.schema == null) {
            return false;
        } else {
            owner = name.schema.owner;
        }

        if (owner == this) {
            return true;
        }

        if (hasRole(owner)) {
            return true;
        }

        return false;
    }

    /**
     * Checks whether this Grantee has administrative privs either directly
     * or indirectly. Otherwise it throws.
     */
    public void checkAdmin() {

        if (!isAdmin()) {
            throw Error.error(ErrorCode.X_42507);
        }
    }

    /**
     * Returns true if this Grantee has administrative privs either directly
     * or indirectly.
     */
    public boolean isAdmin() {
        return isAdmin;
    }

    /**
     * Returns true if this Grantee can create schemas with own authorization.
     */
    public boolean isSchemaCreator() {
        return isAdmin || hasRole(granteeManager.schemaRole);
    }

    /**
     * Returns true if this Grantee can change to a different user.
     */
    public boolean canChangeAuthorisation() {
        return isAdmin || hasRole(granteeManager.changeAuthRole);
    }

    /**
     * Returns true if this Grantee can change to a different user.
     */
    public boolean canPerformScriptOps() {
        return isAdmin || hasRole(granteeManager.scriptOpsRole);
    }

    /**
     * Returns true if this grantee object is for the PUBLIC role.
     */
    public boolean isPublic() {
        return isPublic;
    }

    /**
     * Iteration of all visible grantees, including self. <p>
     *
     * For grantees with admin, this is all grantees.
     * For regular grantees, this is self plus all roles granted directly
     * or indirectly
     */
    public OrderedHashSet visibleGrantees() {

        OrderedHashSet grantees = new OrderedHashSet();
        GranteeManager gm       = granteeManager;

        if (isAdmin()) {
            grantees.addAll(gm.getGrantees());
        } else {
            grantees.add(this);

            Iterator it = getAllRoles().iterator();

            while (it.hasNext()) {
                grantees.add(it.next());
            }
        }

        return grantees;
    }

    public boolean hasNonSelectTableRight(SchemaObject table) {

        if (isFullyAccessibleByRole(table.getName())) {
            return true;
        }

        Right right = (Right) fullRightsMap.get(table.getName());

        if (right == null) {
            return false;
        }

        return right.canAccessNonSelect();
    }

    public boolean hasColumnRights(SchemaObject table, int[] columnMap) {

        if (isFullyAccessibleByRole(table.getName())) {
            return true;
        }

        Right right = (Right) fullRightsMap.get(table.getName());

        if (right == null) {
            return false;
        }

        return right.canAccess((Table) table, columnMap);
    }

    /**
     * Violates naming convention (for backward compatibility).
     * Should be "setAdminDirect(boolean").
     */
    void setAdminDirect() {
        isAdmin = isAdminDirect = true;
    }

    /**
     * Recursive method used with ROLE Grantee objects to set the fullRightsMap
     * and admin flag for all the roles.
     *
     * If a new ROLE is granted to a ROLE Grantee object, the ROLE should first
     * be added to the Set of ROLE Grantee objects (roles) for the grantee.
     * The grantee will be the parameter.
     *
     * If the direct permissions granted to an existing ROLE Grantee is
     * modified no extra initial action is necessary.
     * The existing Grantee will be the parameter.
     *
     * If an existing ROLE is REVOKEed from a ROLE, it should first be removed
     * from the set of ROLE Grantee objects in the containing ROLE.
     * The containing ROLE will be the parameter.
     *
     * If an existing ROLE is DROPped, all its privileges should be cleared
     * first. The ROLE will be the parameter. After calling this method on
     * all other roles, the DROPped role should be removed from all grantees.
     *
     * After the initial modification, this method should be called iteratively
     * on all the ROLE Grantee objects contained in RoleManager.
     *
     * The updateAllRights() method is then called iteratively on all the
     * USER Grantee objects contained in UserManager.
     * @param role a modified, revoked or dropped role.
     * @return true if this Grantee has possibly changed as a result
     */
    boolean updateNestedRoles(Grantee role) {

        boolean hasNested = false;

        if (role != this) {
            for (int i = 0; i < roles.size(); i++) {
                Grantee currentRole = (Grantee) roles.get(i);

                hasNested |= currentRole.updateNestedRoles(role);
            }
        }

        if (hasNested) {
            updateAllRights();
        }

        return hasNested || role == this;
    }

    /**
     * Method used with all Grantee objects to set the full set of rights
     * according to those inherited form ROLE Grantee objects and those
     * granted to the object itself.<p>
     *
     * @todo -- see if this is correct and the currentRole.fullRightsMap
     * is always updated prior to being added to this.fullRightsMap
     */
    void updateAllRights() {

        fullRightsMap.clear();

        isAdmin = isAdminDirect;

        for (int i = 0; i < roles.size(); i++) {
            Grantee currentRole = (Grantee) roles.get(i);

            addToFullRights(currentRole.fullRightsMap);

            isAdmin |= currentRole.isAdmin();
        }

        addToFullRights(directRightsMap);

        if (!isRole && !isPublic && !isSystem) {
            addToFullRights(granteeManager.publicRole.fullRightsMap);
        }
    }

    /**
     * Full or partial rights are added to existing
     */
    void addToFullRights(HashMap map) {

        Iterator it = map.keySet().iterator();

        while (it.hasNext()) {
            Object key      = it.next();
            Right  add      = (Right) map.get(key);
            Right  existing = (Right) fullRightsMap.get(key);

            if (existing == null) {
                existing = add.duplicate();

                fullRightsMap.put(key, existing);
            } else {
                existing.add(add);
            }

            if (add.grantableRights == null) {
                continue;
            }

            if (existing.grantableRights == null) {
                existing.grantableRights = add.grantableRights.duplicate();
            } else {
                existing.grantableRights.add(add.grantableRights);
            }
        }
    }

    /**
     * Full or partial rights are added to existing
     */
    private void addToFullRights(MultiValueHashMap map) {

        Iterator it = map.keySet().iterator();

        while (it.hasNext()) {
            Object   key      = it.next();
            Iterator values   = map.getValuesIterator(key);
            Right    existing = (Right) fullRightsMap.get(key);

            while (values.hasNext()) {
                Right add = (Right) values.next();

                if (existing == null) {
                    existing = add.duplicate();

                    fullRightsMap.put(key, existing);
                } else {
                    existing.add(add);
                }

                if (add.grantableRights == null) {
                    continue;
                }

                if (existing.grantableRights == null) {
                    existing.grantableRights = add.grantableRights.duplicate();
                } else {
                    existing.grantableRights.add(add.grantableRights);
                }
            }
        }
    }

    Right getAllGrantableRights(HsqlName name) {

        if (isAdmin) {
            return name.schema.owner.ownerRights;
        }

        if (name.schema.owner == this) {
            return ownerRights;
        }

        if (roles.contains(name.schema.owner)) {
            return name.schema.owner.ownerRights;
        }

        OrderedHashSet set = getAllRoles();

        for (int i = 0; i < set.size(); i++) {
            Grantee role = (Grantee) set.get(i);

            if (name.schema.owner == role) {
                return role.ownerRights;
            }
        }

        Right right = (Right) fullRightsMap.get(name);

        return right == null || right.grantableRights == null ? Right.noRights
                                                              : right
                                                              .grantableRights;
    }

    /**
     * Retrieves the map object that represents the rights that have been
     * granted on database objects.  <p>
     *
     * The map has keys and values with the following interpretation:
     *
     * <UL>
     * <LI> The keys are generally (but not limited to) objects having
     *      an attribute or value equal to the name of an actual database
     *      object.
     *
     * <LI> Specifically, the keys act as database object identifiers.
     *
     * <LI> The values are Right objects.
     * </UL>
     */
    private MultiValueHashMap getRights() {

        // necessary to create the script
        return directRightsMap;
    }

    /**
     * Grant a role
     */
    void grant(Grantee role) {
        roles.add(role);
    }

    /**
     * Revoke a direct role only
     */
    void revoke(Grantee role) {

        if (!hasRoleDirect(role)) {
            throw Error.error(ErrorCode.X_0P503,
                              role.getName().getNameString());
        }

        roles.remove(role);
    }

    private String roleMapToString(OrderedHashSet roles) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < roles.size(); i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }

            Grantee role = (Grantee) roles.get(i);

            sb.append(role.getName().getStatementName());
        }

        return sb.toString();
    }

    HsqlArrayList getRightsSQL() {

        HsqlArrayList list       = new HsqlArrayList();
        String        roleString = roleMapToString(roles);

        if (!roleString.isEmpty()) {
            StringBuilder sb = new StringBuilder(128);

            sb.append(Tokens.T_GRANT).append(' ').append(roleString);
            sb.append(' ').append(Tokens.T_TO).append(' ');
            sb.append(getName().getStatementName());
            list.add(sb.toString());
        }

        MultiValueHashMap rightsMap = getRights();
        Iterator          dbObjects = rightsMap.keySet().iterator();

        while (dbObjects.hasNext()) {
            Object   nameObject = dbObjects.next();
            Iterator rights     = rightsMap.getValuesIterator(nameObject);

            while (rights.hasNext()) {
                Right         right    = (Right) rights.next();
                StringBuilder sb       = new StringBuilder(128);
                HsqlName      hsqlname = (HsqlName) nameObject;

                switch (hsqlname.type) {

                    case SchemaObject.TABLE :
                    case SchemaObject.VIEW :
                        Table table =
                            granteeManager.database.schemaManager
                                .findUserTable(hsqlname.name,
                                               hsqlname.schema.name);

                        if (table != null) {
                            sb.append(Tokens.T_GRANT).append(' ');
                            sb.append(right.getTableRightsSQL(table));
                            sb.append(' ').append(Tokens.T_ON).append(' ');
                            sb.append(Tokens.T_TABLE).append(' ');
                            sb.append(
                                hsqlname.getSchemaQualifiedStatementName());
                        }
                        break;

                    case SchemaObject.SEQUENCE :
                        NumberSequence sequence =
                            (NumberSequence) granteeManager.database
                                .schemaManager
                                .findSchemaObject(hsqlname.name,
                                                  hsqlname.schema.name,
                                                  SchemaObject.SEQUENCE);

                        if (sequence != null) {
                            sb.append(Tokens.T_GRANT).append(' ');
                            sb.append(Tokens.T_USAGE);
                            sb.append(' ').append(Tokens.T_ON).append(' ');
                            sb.append(Tokens.T_SEQUENCE).append(' ');
                            sb.append(
                                hsqlname.getSchemaQualifiedStatementName());
                        }
                        break;

                    case SchemaObject.DOMAIN :
                        Type domain =
                            (Type) granteeManager.database.schemaManager
                                .findSchemaObject(hsqlname.name,
                                                  hsqlname.schema.name,
                                                  SchemaObject.DOMAIN);

                        if (domain != null) {
                            sb.append(Tokens.T_GRANT).append(' ');
                            sb.append(Tokens.T_USAGE);
                            sb.append(' ').append(Tokens.T_ON).append(' ');
                            sb.append(Tokens.T_DOMAIN).append(' ');
                            sb.append(
                                hsqlname.getSchemaQualifiedStatementName());
                        }
                        break;

                    case SchemaObject.TYPE :
                        Type type =
                            (Type) granteeManager.database.schemaManager
                                .findSchemaObject(hsqlname.name,
                                                  hsqlname.schema.name,
                                                  SchemaObject.DOMAIN);

                        if (type != null) {
                            sb.append(Tokens.T_GRANT).append(' ');
                            sb.append(Tokens.T_USAGE);
                            sb.append(' ').append(Tokens.T_ON).append(' ');
                            sb.append(Tokens.T_TYPE).append(' ');
                            sb.append(
                                hsqlname.getSchemaQualifiedStatementName());
                        }
                        break;

                    case SchemaObject.PROCEDURE :
                    case SchemaObject.FUNCTION :
                    case SchemaObject.SPECIFIC_ROUTINE :
                        SchemaObject routine =
                            granteeManager.database.schemaManager
                                .findSchemaObject(hsqlname.name,
                                                  hsqlname.schema.name,
                                                  hsqlname.type);

                        if (routine != null) {
                            sb.append(Tokens.T_GRANT).append(' ');
                            sb.append(Tokens.T_EXECUTE).append(' ');
                            sb.append(Tokens.T_ON).append(' ');
                            sb.append(Tokens.T_SPECIFIC).append(' ');

                            if (routine.getType() == SchemaObject.PROCEDURE) {
                                sb.append(Tokens.T_PROCEDURE);
                            } else {
                                sb.append(Tokens.T_FUNCTION);
                            }

                            sb.append(' ');
                            sb.append(
                                hsqlname.getSchemaQualifiedStatementName());
                        }
                        break;

                    default :
                }

                if (sb.length() == 0) {
                    continue;
                }

                sb.append(' ').append(Tokens.T_TO).append(' ');
                sb.append(getName().getStatementName());
                list.add(sb.toString());
            }
        }

        return list;
    }
}
