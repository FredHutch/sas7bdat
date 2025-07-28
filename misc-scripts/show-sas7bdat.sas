/********************************************************************
* Copyright (c) 2025 Fred Hutch Cancer Center
* Licensed under the MIT License - see LICENSE file for details
*********************************************************************
* Call as:
*    sas show-sas7bdat.sas -sysparm DATASET
*
* Example:
* To dump the "mydataset" dataset within mydataset.sas7bdat, run as
*    sas_u8 show-sas7bdat.sas -sysparm mydataset.sas7bdat
*
* The dataset must be in the current working directory.
********************************************************************/

*Set an option to be able to load non-ASCII variable names;
OPTIONS VALIDVARNAME=ANY;

%macro readsysparm;

  %global dataset;

  %if %sysevalf(%superq(sysparm)=,boolean) %then %do;
    * If run without parameters;
    %let datasetfile = sample.sas7bdat;
  %end;
  %else %do;
    %let datasetfile = %scan(&sysparm, 1, ' ');
  %end;

  %let datasetfilelen = %sysfunc(length(&datasetfile));
  %let extensionlen   = %sysfunc(length(.sas7bdat));
  %let extensionindex = %sysevalf(&datasetfilelen - &extensionlen);
  %let dataset        = %sysfunc(substr(&datasetfile, 1, &extensionindex));

%mend;
%readsysparm

* Read the dataset;
libname mylib '.' ACCESS=READONLY;


* Convert the dataset to CSV without using PROC EXPORT, since that has a 32K limit on row size.;
* PROC PRINT adds double quotes around all text fields and the column header row;
ODS CSV file="&dataset..csv" encoding="UTF-8";
    proc print data=mylib.&dataset noobs;
run;
ODS CSV close;


* Write the SAS dataset out as an Excel workbook if the number of variables in  ;
* the dataset don't exceed the maximum number of variables that can be exported ;
* to Excel;
%macro exportToExcel(dataset, outfile);
    * Delete the output file, if it exists;
    %sysexec rm -f &outfile;

    * Get the number of variables and observations in the dataset;
    %local totalVariables totalObservations datasetId;
    %let datasetId = %sysfunc(open(mylib.&dataset));
    %if &datasetId %then %do;
        %let totalObservations = %sysfunc(attrn(&datasetId, NLOBS));
        %let totalVariables    = %sysfunc(attrn(&datasetId, NVARS));
        %let rc                = %sysfunc(close(&datasetId));

        %put totalVariables=&totalVariables, totalObservations=&totalObservations;

        * If the dataset has fewer than 256 variables and 64000 observations, then Excel can handle it.;
        %if &totalVariables < 256 and &totalObservations < 64000 %then %do;
            proc export data=mylib.&dataset outfile="&outfile" dbms=xlsx replace;
            run;
        %end;
        %else %do;
            * Put something into the log that indicates why the dataset was not exported to Excel.;
            %if 255 < &totalVariables %then %do;
                %put WARNING: Unable to export Excel workbook &outfile because &dataset has &totalVariables variables.;
                %put WARNING: This exceeds the limit of 255 variables.;
            %end;
            %else %do;
                %put WARNING: Skipping export to Excel workbook &outfile because &dataset has &totalObservations observations.;
                %put WARNING: This is too many observations to be seen practically within Excel.;
            %end;
        %end;
    %end;
    %else %do;
        %put ERROR: open for dataset &dataset failed: %sysfunc(sysmsg());
    %end;
%mend exportToExcel;
%exportToExcel(&dataset, &dataset..xlsx);


* Write the metadata out to a SAS listing;
proc contents data=mylib.&dataset ORDER=VARNUM DETAILS OUT=contents;
run;


* Write the metadata of the dataset out as a CSV.;
proc export data=contents
    outfile="&dataset..metadata.csv"
    dbms=csv
    replace;
run;