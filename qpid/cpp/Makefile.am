#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

LIBTOOL_DEPS = @LIBTOOL_DEPS@

AUTOMAKE_OPTIONS = 1.9.2 foreign
ACLOCAL_AMFLAGS = -I m4

EXTRA_DIST = \
  LICENSE NOTICE README.txt SSL RELEASE_NOTES DESIGN \
  xml/cluster.xml INSTALL-WINDOWS CMakeLists.txt BuildInstallSettings.cmake \
  packaging/NSIS QPID_VERSION.txt bindings/swig_python_typemaps.i \
  bindings/swig_ruby_typemaps.i bindings/swig_perl_typemaps.i

SUBDIRS = managementgen etc src docs/api docs/man examples bindings/qmf bindings/qpid bindings/qmf2

# Update libtool, if needed.
libtool: $(LIBTOOL_DEPS)
	$(SHELL) ./config.status --recheck

check-long: all
	$(MAKE) -C src/tests check-long
	