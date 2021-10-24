/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.map.group;

import org.keycloak.models.map.common.AbstractEntity;

import org.keycloak.models.map.common.UpdatableEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 * @author mhajas
 */
public class MapGroupEntity extends UpdatableEntity.Impl implements AbstractEntity {

    private String id;
    private String realmId;

    private String name;
    private String parentId;
    private Map<String, List<String>> attributes = new HashMap<>();
    private Set<String> grantedRoles = new HashSet<>();

    /**
     * Flag signalizing that any of the setters has been meaningfully used.
     */

    public MapGroupEntity() {}

    public MapGroupEntity(String id, String realmId) {
        this.id = id;
        this.realmId = realmId;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public void setId(String id) {
        if (this.id != null) throw new IllegalStateException("Id cannot be changed");
        this.id = id;
        this.updated |= id != null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.updated |= ! Objects.equals(this.name, name);
        this.name = name;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.updated |= !Objects.equals(this.parentId, parentId);
        this.parentId = parentId;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, List<String>> attributes) {
        this.updated |= ! Objects.equals(this.attributes, attributes);
        this.attributes = attributes;
    }

    public void setAttribute(String name, List<String> value) {
        this.updated |= !this.attributes.containsKey(name) || !this.attributes.get(name).equals(value);
        this.attributes.put(name, value);
    }

    public void removeAttribute(String name) {
        this.updated |= this.attributes.remove(name) != null;
    }

    public List<String> getAttribute(String name) {
        return this.attributes.get(name);
    }

    public String getRealmId() {
        return this.realmId;
    }

    public void setRealmId(String realmId) {
        this.updated |= !Objects.equals(this.realmId, realmId);
        this.realmId = realmId;
    }

    public Set<String> getGrantedRoles() {
        return grantedRoles;
    }

    public void setGrantedRoles(Set<String> grantedRoles) {
        this.updated |= !Objects.equals(this.grantedRoles, grantedRoles);
        this.grantedRoles = grantedRoles;
    }

    public void removeRole(String role) {
        this.updated |= this.grantedRoles.contains(role);
        this.grantedRoles.remove(role);
    }

    public void addGrantedRole(String role) {
        this.updated |= !this.grantedRoles.contains(role);
        this.grantedRoles.add(role);
    }
}
