alter table EDG_ROLES CHANGE COLUMN NAME NAME varchar(255) unique; 
alter table EDG_CR_WORKSPACE CHANGE COLUMN QUOTE QUOTA bigint;
alter table EDG_PAGE_COMMENTS ADD COLUMN PHASE_ID integer;
update table EDG_PAGE_COMMENTS set PHASE_ID=0;