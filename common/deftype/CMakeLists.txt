################################################################################
#    Copyright (C) 2011 HPCC Systems.
#
#    All rights reserved. This program is free software: you can redistribute it and/or modify
#    it under the terms of the GNU Affero General Public License as
#    published by the Free Software Foundation, either version 3 of the
#    License, or (at your option) any later version.
#
#    This program is distributed in the hope that it will be useful,
#    but WITHOUT ANY WARRANTY; without even the implied warranty of
#    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#    GNU Affero General Public License for more details.
#
#    You should have received a copy of the GNU Affero General Public License
#    along with this program.  If not, see <http://www.gnu.org/licenses/>.
################################################################################


# Component: deftype 
#####################################################
# Description:
# ------------
#    Cmake Input File for deftype
#####################################################

project( deftype ) 

set (    SRCS 
         deffield.cpp 
         deftype.cpp 
         defvalue.cpp 
         
         deffield.hpp
         deftype.hpp
         defvalue.hpp
         
    )

include_directories ( 
         ./../../common/deftype 
         ./../../rtl/eclrtl 
         ./../../rtl/nbcd 
         ./../../system/include 
         ./../../system/jlib 
    )

ADD_DEFINITIONS( -D_USRDLL -DDEFTYPE_EXPORTS )

HPCC_ADD_LIBRARY( deftype SHARED ${SRCS} )
install ( TARGETS deftype DESTINATION ${OSSDIR}/lib )
target_link_libraries ( deftype 
         jlib 
         nbcd
         eclrtl 
    )

