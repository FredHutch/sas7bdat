This file contains a high-level design of the library and some implementation notes.

Purpose
-------
The purpose of this library is to allow JVM-based applications to export data sets in SAS7BDAT format so that they can be read by SAS programs.
As such, it only needs to support one valid form for any given data set, which was chosen to be 64-bit, UNIX, little-endian, UTF-8.
Since SAS7BDAT files are used for information exchange between corporations, I presume that SAS running on a different architecture will be able to read these data sets.

Class Structure and Responsibilities
------------------------------------
The Metadata, Variable, and Format classes are immutable POJOs for describing the shape of the data set.
They are responsible for validating their input, every instance of these classes should only contain legal values.

The `Sas7bdatExporter` class is responsible for writing the SAS7BDAT file.
This is called an "Exporter" and not "Writer" because Java has a "Writer" class, which `Sas7bdatExporter` necessarily can't extend.
That is, the `Sas7bdatExporter` isn't called "Sas7bdatWriter" because it's not a `Writer`.

A section of metadata in a SAS7BDAT are called `Subheader` because this is the term used by Dr. Shotwell's reverse-engineer SAS7BDAT specification.
There are three main types: `FixedSizeSubheader`, `VariableSizeSubheader`, and `TerminalSubheader`.
The `FixedSizeSubheader` classes all have a constant size.
The size of a `VariableSizeSubheader` changes depending on its content.
The `TerminalSubheader` has no size, it's not really a subheader but a way for a SAS7BDAT to indicate when there are no more subheaders on a page.

Each of the subheaders contains metadata for the data set.
The different subclasses contain partially overlapping information and some subheaders refer to other subheaders.
When one subheader needs access to information in a different subheader, it is provided a `Sas7bdatPageLayout` class, which can be used to iterate over all of a document's subheaders.
Each subheader holds all of its information in a structured form until its write() method is invoked, at which time the subheader is serialized the way SAS expects.

There are dependency cycles in the subheaders, a subheader may depend on information in another subheader, which depends on information from the first subheader.
To handle this, it's `Sas7bdatExporter`'s responsibility to construct all subheaders in a data set before any of them are written.
Each subheader has a lazy implementation; they don't read information from other subheaders until their write() method is invoked.

Testing Strategy
----------------
The SAS7BDAT strategy 
The source code has many assert statements.
These assert statements should not fail for any input given by the public API; invalid input should cause an exception to be thrown.
If an assert fails, it implies there's a defect in the library.

The Junit tests for the Subheader classes can only test that the output writes what it assumed to be correct.
The tests don't know if it really is correct.
Fixing a bug in a Subheader class will probably cause its test to fail.
On the other hand, refactoring shouldn't cause the tests to fail, so these tests do have some value.

The Junit for `Sas7bdatExporter` is the closest thing to end-to-end testing.
It uses the Parso library to read what `Sas7bdatExporter` wrote.
The Parso library is forgiving and can read data from corrupt SAS7BDAT files that causes SAS to crash.
These tests aren't perfect, but they're better than nothing.

The script `misc-scripts/test-random-sas7bdat.groovy` uses SAS to read a dataset that `Sas7bdatExporter` writes.
This cannot be run in automation because it requires a SAS license.
Due to limitation of SAS's CSV export, it can't test the following:
1. large datasets
2. datasets with observations wider than 32K
3. variations on formats

`test-random-sas7bdat.groovy` has two major modes.
In the first mode, it generates a data set at random, writes it with `Sas7bdatExporter` and confirms that SAS can read what intended from the SAS7BDAT.
It also writes a SAS program that generates an identical data set, so that it can be compared with what `Sas7bdatExporter` wrote.
It can do this in a loop.
By experience, if a bug hasn't been found in 100 iteration, then `test-random-sas7bdat.groovy` won't ever find it.

In the second mode, `test-random-sas7bdat.groovy` tests a data set that is defined in a JSON file.
This can re-test a data set that previously failed, generating it with `Sas7bdatExporter` and reading it with SAS.
There are several such datasets in the test-data directory.