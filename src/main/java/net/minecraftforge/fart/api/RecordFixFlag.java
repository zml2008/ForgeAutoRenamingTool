/*
 * Forge Auto Renaming Tool
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fart.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Record features to fix
 */
public enum RecordFixFlag {
    /**
     * Fix record components metadata.
     */
    COMPONENTS,
    /**
     * Fix generic signature attribute on record classes.
     */
    SIGNATURE;

    /**
     * Get a new modifiable set of all record fix flags.
     *
     * @return the record fix flags to use.
     */
    public static Set<RecordFixFlag> all() {
        return EnumSet.allOf(RecordFixFlag.class);
    }
}
