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
package io.soabase.config.mocks;

import io.soabase.config.ComposedConfigurationBuilder;
import io.soabase.config.service.ComposedConfigurationServiceFactory;

public class Factory2 implements ComposedConfigurationServiceFactory
{
    @Override
    public void addToBuilder(ComposedConfigurationBuilder<?> builder)
    {
        builder.add("t2", TestConfiguration2.class);
    }
}