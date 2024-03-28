import React, { useState, useRef, useEffect, useContext } from "react";
import { FormattedMessage, injectIntl, useIntl } from "react-intl";
import {
  Heading,
  Grid,
  Column,
  Section,
  Breadcrumb,
  BreadcrumbItem,
  Loading,
} from "@carbon/react";


const PatientEntryByProject = () => {
  const intl = useIntl();

  return (
    <>
      <Grid fullWidth={true}>
        <Column lg={16}>
          <Breadcrumb>
            <BreadcrumbItem href="/">
              {intl.formatMessage({ id: "home.label" })}
            </BreadcrumbItem>
          </Breadcrumb>
        </Column>
      </Grid>

    </>
  );
};

export default PatientEntryByProject;