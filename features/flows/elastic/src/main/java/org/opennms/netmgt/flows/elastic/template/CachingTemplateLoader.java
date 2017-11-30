/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2017-2017 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2017 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.flows.elastic.template;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Caches the loading of templates.
 */
public class CachingTemplateLoader implements TemplateLoader {

    private final LoadingCache<String, String> cache;

    public CachingTemplateLoader(final TemplateLoader delegate) {
        Objects.requireNonNull(delegate);

        this.cache = CacheBuilder.newBuilder().maximumSize(100).build(new CacheLoader<String, String>() {
            @Override
            public String load(String resource) throws Exception {
                return delegate.load(resource);
            }
        });
    }

    @Override
    public String load(String resource) throws IOException {
        try {
            return cache.get(resource);
        } catch (ExecutionException e) {
            throw new IOException("Could not read data from cache", e);
        }
    }

}
