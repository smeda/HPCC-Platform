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


#ifndef _JSCM_HPP_
#define _JSCM_HPP_

#undef interface
#define implements   public
#define extends      public

#ifdef _MSC_VER
#define interface    struct __declspec(novtable)
#else
#define interface    struct 
#endif

interface IInterface
{
    virtual void Link() const = 0;
    virtual bool Release() const = 0;
};

template <class X> inline void Link(X * ptr)    { if (ptr) ptr->Link(); }
template <class X> inline void Release(X * ptr) { if (ptr) ptr->Release(); }

#define QUERYINTERFACE(ptr, TYPE)   (dynamic_cast<TYPE *>(ptr))

//A simple object container/smart pointer
template <class CLASS> class OwnedPtr
{
public:
    inline OwnedPtr()                        { ptr = NULL; }
    inline OwnedPtr(CLASS * _ptr)            { ptr = _ptr; }
    inline ~OwnedPtr()                       { delete ptr; }

    void operator = (CLASS * _ptr)
    {
        if (ptr)
            delete ptr;
        ptr = _ptr;
    }
    inline CLASS * operator -> () const         { return ptr; }
    inline operator CLASS *() const             { return ptr; }

    inline void clear()                         { CLASS *temp=ptr; ptr=NULL; delete temp; }
    inline CLASS * get() const                  { return ptr; }
    inline CLASS * getClear()                   { CLASS * temp = ptr; ptr = NULL; return temp; }
    inline void setown(CLASS * _ptr)            { CLASS * temp = ptr; ptr = _ptr; delete temp; }

private:
    inline OwnedPtr(const OwnedPtr<CLASS> & other);
    void operator = (const OwnedPtr<CLASS> & other);
    void setown(const OwnedPtr<CLASS> &other);

private:
    CLASS * ptr;
};


//This base class implements a shared pointer based on a link count held in the object.
//The two derived classes Owned and Linked should be used as the concrete types to construct a shared object
//from a pointer.
template <class CLASS> class Shared
{
public:
    inline Shared()                              { ptr = NULL; }
    inline Shared(CLASS * _ptr, bool owned)      { ptr = _ptr; if (!owned && _ptr) _ptr->Link(); }
    inline Shared(const Shared<CLASS> & other)   { ptr = other.getLink(); }
    inline ~Shared()                             { ::Release(ptr); }

    inline Shared<CLASS> & operator = (const Shared<CLASS> & other) { this->set(other.get()); return *this;  }

    inline CLASS * operator -> () const         { return ptr; } 
    inline operator CLASS *() const             { return ptr; } 

    inline void clear()                         { CLASS *temp=ptr; ptr=NULL; ::Release(temp); }
    inline CLASS * get() const                  { return ptr; }
    inline CLASS * getClear()                   { CLASS * temp = ptr; ptr = NULL; return temp; }
    inline CLASS * getLink() const              { if (ptr) ptr->Link(); return ptr; }
    inline void set(CLASS * _ptr)
    {
        CLASS * temp = ptr;
        if (temp != _ptr)
        {
            ::Link(_ptr);
            ptr = _ptr;
            ::Release(temp);
        }
    }
    inline void set(const Shared<CLASS> &other) { this->set(other.get()); }
    inline void setown(CLASS * _ptr)            { CLASS * temp = ptr; ptr = _ptr; ::Release(temp); }
    inline void swap(Shared<CLASS> & other)     { CLASS * temp = ptr; ptr = other.ptr; other.ptr = temp; }
    
protected:
    inline Shared(CLASS * _ptr)                  { ptr = _ptr; } // deliberately protected

private:
    inline void setown(const Shared<CLASS> &other); // illegal - going to cause a -ve leak
    inline Shared<CLASS> & operator = (const CLASS * other);

private:
    CLASS * ptr;
};


//An Owned Shared object takes ownership of the pointer that is passed in the constructor.
template <class CLASS> class Owned : public Shared<CLASS>
{
public:
    inline Owned()                              { }
    inline Owned(CLASS * _ptr) : Shared<CLASS>(_ptr)   { }

    inline Shared<CLASS> & operator = (const Shared<CLASS> & other) { this->set(other.get()); return *this;  }

private:
    inline Owned(const Shared<CLASS> & other); // Almost certainly a bug
    inline Owned<CLASS> & operator = (const CLASS * other);
};


//A Linked Shared object takes does not take ownership of the pointer that is passed in the constructor.
template <class CLASS> class Linked : public Shared<CLASS>
{
public:
    inline Linked()                         { }
    inline Linked(CLASS * _ptr) : Shared<CLASS>(LINK(_ptr)) { }
    inline Linked(const Shared<CLASS> & other) : Shared<CLASS>(other) { }

    inline Shared<CLASS> & operator = (const Shared<CLASS> & other) { this->set(other.get()); return *this;  }

private:
    inline Linked<CLASS> & operator = (const CLASS * other);
};

// IStringVal manages returning of arbitrary null-terminated string data between systems that may not share heap managers
interface IStringVal
{
    virtual const char * str() const = 0;
    virtual void set(const char * val) = 0;
    virtual void clear() = 0;
    virtual void setLen(const char * val, unsigned length) = 0;
    virtual unsigned length() const = 0;
};


// IDataVal manages returning of arbitrary unterminated binary data between systems that may not share heap managers
interface IDataVal
{
    virtual const void * data() const = 0;
    virtual void clear() = 0;
    virtual void setLen(const void * val, unsigned length) = 0;
    virtual unsigned length() const = 0;
    virtual void * reserve(unsigned length) = 0;
};


// IIterator 
interface IIterator : extends IInterface
{
    virtual bool first() = 0;
    virtual bool next() = 0;
    virtual bool isValid() = 0;
    virtual IInterface & query() = 0;
    virtual IInterface & get() = 0;
};

template <class C> 
interface IIteratorOf : public IInterface
{
public:
    virtual bool first() = 0;
    virtual bool next() = 0;
    virtual bool isValid() = 0;
    virtual C  & query() = 0;
            C  & get() { C &c = query(); c.Link(); return c; }
};

#define ForEach(i)              for((i).first();(i).isValid();(i).next())

typedef IInterface * IInterfacePtr;
typedef Owned<IInterface> OwnedIInterface;
typedef Linked<IInterface> LinkedIInterface;

template <class X> inline X * LINK(X * ptr)     { if (ptr) ptr->Link(); return ptr; }
template <class X> inline X & OLINK(X & obj)        { obj.Link(); return obj; }
template <class X> inline X * LINK(const Shared<X> &ptr) { return ptr.getLink(); }

#endif
