/**
 * Copyright 2014 Jordan Zimmerman
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
package io.soabase.admin.details;

public class IndexMapping
{
    private final String path;
    private final String file;
    private final String key;
    private final boolean isAuthServlet;

    public IndexMapping(String path, String file)
    {
        this(path, file, false);
    }

    public IndexMapping(String path, String file, boolean isAuthServlet)
    {
        this.isAuthServlet = isAuthServlet;
        this.key = path.equals("") ? "/" : path;
        this.path = path;
        this.file = file;
    }

    public String getPath()
    {
        return path;
    }

    public String getFile()
    {
        return file;
    }

    public String getKey()
    {
        return key;
    }

    public boolean isAuthServlet()
    {
        return isAuthServlet;
    }
}
