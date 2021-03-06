/*##############################################################################

    Copyright (C) 2011 HPCC Systems.

    All rights reserved. This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
############################################################################## */

#include "roxiemem.hpp"
#include "roxierowbuff.hpp"
#include "jlog.hpp"
#include <new>

#ifndef _WIN32
#include <sys/mman.h>
#endif

const unsigned RowArrayActivityId = 0xc3f7;

namespace roxiemem {

RoxieOutputRowArray::RoxieOutputRowArray(IRowManager * _rowManager, rowidx_t initialSize, size32_t _commitDelta) :
    rowManager(_rowManager), commitDelta(_commitDelta)
{
    if (initialSize)
    {
        rows = static_cast<const void * *>(rowManager->allocate(initialSize * sizeof(void*), RowArrayActivityId));
        maxRows = RoxieRowCapacity(rows) / sizeof(void *);
    }
    else
    {
        rows = NULL;
        maxRows = 0;
    }
    commitRows = 0;
    numRows = 0;
    firstRow = 0;
}


//The following can be accessed from the reader without any need to lock
const void * RoxieOutputRowArray::query(rowidx_t i) const
{
    RoxieOutputRowArrayLock block(*this);
    if (i < firstRow || i >= commitRows)
        return NULL;
    return rows[i];
}

const void * RoxieOutputRowArray::getClear(rowidx_t i)
{
    RoxieOutputRowArrayLock block(*this);
    if (i < firstRow || i >= commitRows)
        return NULL;
    const void * row = rows[i];
    rows[i] = NULL;
    return row;
}

const void * RoxieOutputRowArray::get(rowidx_t i) const
{
    RoxieOutputRowArrayLock block(*this);
    if (i < firstRow || i >= commitRows)
        return NULL;

    const void * row = rows[i];
    if (row)
        LinkRoxieRow(row);
    return row;
}


const void * * RoxieOutputRowArray::getBlock(rowidx_t readRows)
{
    dbgassertex(firstRow+readRows <= commitRows);
    return rows + firstRow;
}

void RoxieOutputRowArray::noteSpilled(rowidx_t spilledRows)
{
    firstRow += spilledRows;
}


void RoxieOutputRowArray::flush()
{
    RoxieOutputRowArrayLock block(*this);
    dbgassertex(numRows >= commitRows);
    //This test could be improved...
    if (firstRow != 0 && firstRow == commitRows)
    {
        //A block of rows was removed - copy these rows to the start of the block.
        memmove(rows, rows+firstRow, (numRows-firstRow) * sizeof(void *));
        numRows -= firstRow;
        firstRow = 0;
    }

    commitRows = numRows;
}

void RoxieOutputRowArray::clearRows()
{
    for (rowidx_t i = firstRow; i < numRows; i++)
        ReleaseRoxieRow(rows[i]);
    firstRow = 0;
    numRows = 0;
    commitRows = 0;
}

void RoxieOutputRowArray::kill()
{
    clearRows();
    maxRows = 0;
    ReleaseRoxieRow(rows);
    rows = NULL;
}

void RoxieOutputRowArray::transferRows(rowidx_t & outNumRows, const void * * & outRows)
{
    assertex(firstRow == 0);  // could allow that to be transferred as well
    outNumRows = numRows;
    outRows = rows;
    //firstRows = 0;
    numRows = 0;
    commitRows = 0;
    maxRows = 0;
    rows = NULL;
}

//---------------------------------------------------------------------------------------------------------------------

bool DynamicRoxieOutputRowArray::ensure(rowidx_t requiredRows)
{
    unsigned newSize = maxRows;
    //This condition must be <= at least 1/scaling factor below otherwise you'll get an infinite loop.
    if (newSize <= 4)
        newSize = requiredRows;
    else
    {
        //What algorithm should we use to increase the size?  Trading memory usage against copying row pointers.
        // adding 50% would reduce the number of allocations.
        // anything below 32% would mean that blocks n,n+1 when freed have enough space for block n+3 which might
        //   reduce fragmentation.
        //Use 25% for the moment.  It should possibly be configurable - e.g., higher for thor global sort.
        while (newSize < requiredRows)
            newSize += newSize/4;
    }

    const void * * newRows;
    try
    {
        newRows = static_cast<const void * *>(rowManager->allocate(newSize * sizeof(void*), RowArrayActivityId));
        if (!newRows)
            return false;
    }
    catch (IException * e)
    {
        //Pahological cases - not enough memory to reallocate the target row buffer, or no contiguous pages available.
        unsigned code = e->errorCode();
        if ((code == ROXIEMM_MEMORY_LIMIT_EXCEEDED) || (code == ROXIEMM_MEMORY_POOL_EXHAUSTED))
        {
            e->Release();
            return false;
        }
        throw;
    }

    //Only the writer is allowed to reallocate rows (otherwise append can't be optimized), so rows is valid outside the lock
    const void * * oldRows = rows;
    {
        RoxieOutputRowArrayLock block(*this);
        oldRows = rows;
        memcpy(newRows, oldRows+firstRow, (numRows - firstRow) * sizeof(void*));
        numRows -= firstRow;
        commitRows -= firstRow;
        firstRow = 0;
        rows = newRows;
        maxRows = RoxieRowCapacity(newRows) / sizeof(void *);
    }

    ReleaseRoxieRow(oldRows);
    return true;
}

//============================================================================================================

void RoxieSimpleInputRowArray::kill()
{
    if (rows)
    {
        for (rowidx_t i = firstRow; i < numRows; i++)
            ReleaseRoxieRow(rows[i]);
        firstRow = 0;
        numRows = 0;
        ReleaseRoxieRow(rows);
        rows = NULL;
    }
}

void RoxieSimpleInputRowArray::transferFrom(RoxieOutputRowArray & donor)
{
    kill();
    donor.transferRows(numRows, rows);
}

//============================================================================================================

} // namespace roxiemem

//============================================================================================================

#ifdef _USE_CPPUNIT
#include <cppunit/extensions/HelperMacros.h>
#define ASSERT(a) { if (!(a)) CPPUNIT_ASSERT(a); }

namespace roxiemem {

class IStdException : extends std::exception
{
    Owned<IException> jException;
public:
    IStdException(IException *E) throw() : jException(E) {};
   virtual ~IStdException() throw() {}
};

class RoxieBufferTests : public CppUnit::TestFixture
{
    CPPUNIT_TEST_SUITE( RoxieBufferTests );
    CPPUNIT_TEST_SUITE_END();
    const IContextLogger &logctx;

public:
    RoxieBufferTests() : logctx(queryDummyContextLogger())
    {
    }

    ~RoxieBufferTests()
    {
    }
};

CPPUNIT_TEST_SUITE_REGISTRATION( RoxieBufferTests );
CPPUNIT_TEST_SUITE_NAMED_REGISTRATION( RoxieBufferTests, "RoxieMemTests" );

} // namespace roxiemem
#endif
