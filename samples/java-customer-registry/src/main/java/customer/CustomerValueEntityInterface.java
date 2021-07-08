/*
 * Copyright 2021 Lightbend Inc.
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

package customer;

import com.akkaserverless.javasdk.Effect;
import com.akkaserverless.javasdk.valueentity.CommandContext;
import com.akkaserverless.javasdk.valueentity.ValueEntityBase;
import com.google.protobuf.Empty;
import customer.api.CustomerApi;
import customer.domain.CustomerDomain;

import java.util.Optional;

public abstract class CustomerValueEntityInterface
    extends ValueEntityBase<CustomerDomain.CustomerState> {

  public abstract Effect<CustomerApi.Customer> getCustomer(
      CustomerApi.GetCustomerRequest request,
      CustomerDomain.CustomerState currentState,
      CommandContext<CustomerDomain.CustomerState> context);

  public abstract Effect<Empty> create(
      CustomerApi.Customer customer,
      CustomerDomain.CustomerState currentState,
      CommandContext<CustomerDomain.CustomerState> context);

  public abstract Effect<Empty> changeName(
      CustomerApi.ChangeNameRequest request,
      CustomerDomain.CustomerState currentState,
      CommandContext<CustomerDomain.CustomerState> context);
}
