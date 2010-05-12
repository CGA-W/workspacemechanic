/*******************************************************************************
 * Copyright (C) 2009, Google Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package com.google.eclipse.mechanic.plugin.ui;

import com.google.eclipse.mechanic.MechanicService;
import com.google.eclipse.mechanic.RepairDecisionProvider;
import com.google.eclipse.mechanic.StatusChangeListener;
import com.google.eclipse.mechanic.StatusChangedEvent;
import com.google.eclipse.mechanic.plugin.core.MechanicPreferences;

import org.eclipse.mylyn.internal.provisional.commons.ui.AbstractNotificationPopup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Hyperlink;

/**
 * A {@link StatusChangeListener} that appears when tasks fail.
 *
 * <p>Use cases and conditions for when a popup will appear.
 *
 * <p>The popup appears when the mechanic announces failure. But if we showed the
 * popup every time the mechanic returned failure, it would pop up way too often,
 * and when that happens, people will turn it off, and its value will be lost.
 *
 * <p>Case 1: when the workbench starts, the first failure will cause the popup
 * to appear.
 *
 * <p>Case 1a: (Specialization) Restarting the mechanic: When the user has stopped
 * the mechanic service (right-click on the trim widget, select Stop Service),
 * and then restarts it, the popup will appear on first-failure.
 *
 * <p>Case 1b: (Specialization) Reenabling showing the popup: if the popup
 * notifier was turned off, and subsequently turned on, the poup will appear
 * the first time the mechanic returns a failure notification.
 *
 * <p>Case 2: popup is already shown: If the mechanic announces failure, and the
 * popup is already shown, then don't show a second popup a second time.
 *
 * <p>Case 3: subsequent failures: If the mechanic has announced failure, then the
 * popup will not appear a second time, at least, not until the mechanic has returned
 * a status of passed. That addresses this flow of statuses: (the "#" means that
 * the popup appears):
 *
 * failed # failed failed (user fixes) passed failed #
 *
 * <p>Case 3a: (Specialization) Bug in a repair results in perpetual failure:
 * Even though the user tries to fix failed tasks, if one of them, no matter
 * what, continues to fail, the popup will not appear.
 *
 * @author konigsberg@google.com (Robert Konigsberg)
 */
public class PopupNotifier {

  // Popup appears for two minutes.
  private static final long POPUP_TIMEOUT_MILLIS = 1000L * 60 * 2;

  private final StatusChangeListener statusChangeListener;

  private final MechanicService service;

  /**
   * controls whether the next failure from the mechanic should result in showing the popup.
   */
  private volatile boolean showOnFailure = true;

  /**
   * Identifies whether the popup is currently visible. We don't want to open a second
   * instance while one is open.
   */
  private volatile boolean visible = false;

  public PopupNotifier(MechanicService mechanicService) {
    this.service = mechanicService;
    this.statusChangeListener = new StatusChangeListener() {
      public void statusChanged(StatusChangedEvent event) {
        switch (event.getStatus()) {
          case FAILED:
            if (MechanicPreferences.isShowPopup()) {
              if (showOnFailure) {
                showPopup();
              }
              showOnFailure = false;
            } else {
              // By setting showOnFailure here, the popup will appear once the preference is unset
              showOnFailure = true;
            }
            break;

          case PASSED:
          case STOPPED:
            /*
             * Once the mechanic has passed all tasks, or has stopped analyzing, the
             * first subsequent failure should show the popup.
             */
            showOnFailure = true;
            break;

          case UPDATING:
            // Do nothing
            break;

          default:
            throw new IllegalArgumentException("Unknown status: " + event.getStatus());
        }
      }
    };
  }

  public void initialize() {
    service.addTaskStatusChangeListener(statusChangeListener);
  }

  public void dispose() {
    service.removeTaskStatusChangeListener(statusChangeListener);
  }

  private void showPopup() {
    if (visible) {
      return;
    }
    final Display display =
        Display.getCurrent() != null ? Display.getCurrent() : Display.getDefault();

    AbstractNotificationPopup popup = new AbstractNotificationPopup(display) {
      @Override
      protected void createContentArea(Composite parent) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(
            "The Workspace Mechanic found\n" +
            "issues that need your attention.");
        label.setBackground(parent.getBackground());

        createHyperlink(display, parent, "View and correct configuration issues", new Runnable() {
          public void run() {
            close();
            RepairDecisionProvider cpro = new UserChoiceDecisionProvider(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow());
            service.getRepairManager(cpro).run();
          }
        });

        createHyperlink(display, parent, "Disable this popup", new Runnable() {
          public void run() {
            close();
            MechanicPreferences.doNotShowPopup();
          }
        });
      }

      /**
       * Go ahead and close, but also indicate that the popup is closed.
       */
      @Override
      public boolean close() {
        boolean returnValue = super.close();
        visible = false;
        return returnValue;
      }
    };

    popup.setDelayClose(POPUP_TIMEOUT_MILLIS);
    visible = true;
    popup.open();
  }

  private Hyperlink createHyperlink(final Display display, Composite parent, String text,
      final Runnable runnable) {
    Hyperlink hyperlink = new Hyperlink(parent, SWT.WRAP);
    hyperlink.setBackground(parent.getBackground());
    hyperlink.setText(text);
    hyperlink.setUnderlined(true);
    hyperlink.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      public void linkActivated(HyperlinkEvent e) {
        display.syncExec(runnable);
      }
    });
    return hyperlink;
  }
}