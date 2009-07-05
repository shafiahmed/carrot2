/*
 * Carrot2 project.
 *
 * Copyright (C) 2002-2009, Dawid Weiss, Stanisław Osiński.
 * All rights reserved.
 *
 * Refer to the full license file "carrot2.LICENSE"
 * in the root folder of the repository checkout or at:
 * http://www.carrot2.org/carrot2.LICENSE
 */

package org.carrot2.workbench.core.ui;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;

import org.carrot2.util.attribute.*;
import org.carrot2.util.attribute.BindableDescriptor.GroupingMethod;
import org.carrot2.workbench.core.helpers.GUIFactory;
import org.carrot2.workbench.core.helpers.Utils;
import org.carrot2.workbench.core.ui.widgets.CScrolledComposite;
import org.carrot2.workbench.editors.AttributeEvent;
import org.carrot2.workbench.editors.AttributeListenerAdapter;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.*;
import org.eclipse.jface.layout.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.IEncodingSupport;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.part.Page;
import org.eclipse.ui.progress.UIJob;


/**
 * A single benchmark view page is associated with a given editor (and hence with the
 * given input and algorithm).
 */
final class BenchmarkViewPage extends Page
{
    private final static String START_TEXT = "Start";
    private final static String STOP_TEXT = "Stop";

    /** The configuration we are benchmarking. */
    private final SearchEditor editor;

    /** Current benchmark settings. */
    private BenchmarkSettings benchmarkSettings = new BenchmarkSettings();

    /** Scroller container for all components. */
    private CScrolledComposite scroller;

    private Label avgTimeLabel;
    private Label minTimeLabel;
    private Label maxTimeLabel;
    private Label stddevLabel;
    private Label statusLabel;
    private Button startButton;
    private ProgressBar progressBar;

    /**
     * Benchmarking job is part of Eclipse's infrastructure.
     */
    private BenchmarkJob benchmarkJob;

    /**
     * Update benchmarking job results.
     */
    private Job benchmarkStatusUpdateJob = new UIJob("Benchmark update") {
        {
            this.setPriority(Job.SHORT);
            this.setSystem(true);
        }

        @Override
        public IStatus runInUIThread(IProgressMonitor monitor)
        {
            if (benchmarkJob != null)
            {
                updateBenchmark(benchmarkJob.statistics);
                this.schedule(1000);
            }
            return Status.OK_STATUS;
        }
    };

    /**
     * Job tracking.
     */
    private IJobChangeListener jobListener = new JobChangeAdapter()
    {
        @Override
        public void done(IJobChangeEvent ijobchangeevent)
        {
            Display.getDefault().asyncExec(new Runnable() {
                public void run()
                {
                    updateBenchmark(benchmarkJob.statistics);
                    endBenchmark();
                }
            });
        }
    };

    /**
     * Start benchmarking job.
     */
    private void startBenchmark()
    {
        assert Display.getCurrent() != null;

        startButton.setText(STOP_TEXT);

        benchmarkJob = new BenchmarkJob(editor.getSearchResult().getInput(), benchmarkSettings);
        updateBenchmark(benchmarkJob.statistics);
        benchmarkJob.addJobChangeListener(jobListener);
        benchmarkJob.schedule();

        benchmarkStatusUpdateJob.schedule();        
    }

    /**
     * Update benchmark GUI state.
     */
    private void updateBenchmark(BenchmarkStatistics statistics)
    {
        assert Display.getCurrent() != null;
        
        if (statistics.round == 0)
        {
            this.progressBar.setMaximum(statistics.benchmarkRounds + statistics.warmupRounds);
            this.progressBar.setMinimum(0);
            this.progressBar.setSelection(0);

            avgTimeLabel.setText("");
            stddevLabel.setText("");
            minTimeLabel.setText("");
            maxTimeLabel.setText("");
            statusLabel.setText("waiting for data");
        }
        else
        {
            this.progressBar.setSelection(statistics.round);

            avgTimeLabel.setText(formatSeconds(statistics.avg));
            stddevLabel.setText(formatSeconds(statistics.stdDev));
            minTimeLabel.setText(formatSeconds(statistics.min));
            maxTimeLabel.setText(formatSeconds(statistics.max));
            
            String status;
            if (statistics.round < statistics.warmupRounds) 
            {
                status = "warmup";
            }
            else if (statistics.round == statistics.warmupRounds + statistics.benchmarkRounds) 
            {
                status = "done";
            }
            else
            {
                status = "benchmark";
            }
            statusLabel.setText(String.format("Round %d (%s)", statistics.round, status));
        }
    }

    /*
     * 
     */
    private String formatSeconds(double v)
    {
        return String.format(Locale.ENGLISH, "%.03f sec.", v / 1000.0);
    }

    /**
     * Cleans up after a benchmarking job.
     */
    private void endBenchmark()
    {
        assert Display.getCurrent() != null;
        
        try
        {
            if (benchmarkSettings.openLogsInEditor)
            {
                final File file = benchmarkJob.logFile;
                final IFileStore fileStore = EFS.getLocalFileSystem().fromLocalFile(file);
                final IEditorPart part = getSite().getWorkbenchWindow().getActivePage().openEditor(
                    new FileStoreEditorInput(fileStore), EditorsUI.DEFAULT_TEXT_EDITOR_ID);

                ((IEncodingSupport) part.getAdapter(IEncodingSupport.class)).setEncoding("UTF-8");                
            }
        }
        catch (Exception e)
        {
            Utils.logError(e, true);
        }

        benchmarkJob = null;
        startButton.setText(START_TEXT);
    }

    /*
     * 
     */
    public BenchmarkViewPage(SearchEditor editor)
    {
        this.editor = editor;
    }

    /*
     * 
     */
    @Override
    public void createControl(Composite parent)
    {
        this.scroller = new CScrolledComposite(parent, SWT.H_SCROLL | SWT.V_SCROLL);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);

        final Composite innerComposite = GUIFactory.createSpacer(scroller);
        final GridLayout gridLayout = (GridLayout) innerComposite.getLayout();
        gridLayout.numColumns = 1;
        gridLayout.verticalSpacing = LayoutConstants.getSpacing().y;
        scroller.setContent(innerComposite);

        createBenchmarkPanel(innerComposite);
        createSeparator(innerComposite);
        createSettingsPanel(innerComposite);
    }

    /**
     * Create separator between settings and the benchmark panel.
     */
    private void createSeparator(Composite parent)
    {
        final Label label = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        label.setLayoutData(
            GridDataFactory.fillDefaults().grab(true, false).create());
    }

    /**
     * Create settings panel.
     */
    private Control createSettingsPanel(Composite parent)
    {
        final BindableDescriptor descriptor = 
            BindableDescriptorBuilder.buildDescriptor(benchmarkSettings, true);

        final HashMap<String, Object> attrs = descriptor.getDefaultValues();
        final AttributeGroups panel = new AttributeGroups(
            parent, descriptor, GroupingMethod.GROUP, null, attrs);
        panel.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).create());

        // Link changes in the editor to settings object.
        panel.addAttributeListener(new AttributeListenerAdapter()
        {
            public void valueChanged(AttributeEvent event)
            {
                attrs.put(event.key, event.value);
                try
                {
                    AttributeBinder.bind(benchmarkSettings, attrs, Input.class);
                }
                catch (InstantiationException e)
                {
                    Utils.logError(e, true);
                }
            }
        });
        panel.collapseAll();

        return panel;
    }
    
    /**
     * Create benchmark panel component.
     */
    private Control createBenchmarkPanel(Composite parent)
    {
        final Composite panel = new Composite(parent, SWT.NONE);
        panel.setLayout(
            GridLayoutFactory.fillDefaults()
            .numColumns(1)
            .margins(0, 0).create());
        panel.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).create());

        // Progress bar | start/stop button
        final Composite row1 = new Composite(panel, SWT.NONE);
        row1.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).create());
        row1.setLayout(GridLayoutFactory.fillDefaults()
            .numColumns(2).margins(0, 0).create());

        progressBar = new ProgressBar(row1, SWT.HORIZONTAL | SWT.SMOOTH);
        progressBar.setMinimum(0);
        progressBar.setMaximum(100);
        progressBar.setSelection(0);
        progressBar.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).create());

        startButton = new Button(row1, SWT.PUSH);
        startButton.setText(START_TEXT);
        startButton.setLayoutData(GridDataFactory.fillDefaults().create());
        startButton.addSelectionListener(new SelectionAdapter()
        {
            public void widgetSelected(SelectionEvent selectionevent)
            {
                if (benchmarkJob == null)
                {
                    startBenchmark();
                }
            }
        });

        // On-line reports and statistics.
        final Composite row2 = new Composite(panel, SWT.NONE);
        row2.setLayoutData(GridDataFactory.fillDefaults().create());
        row2.setLayout(GridLayoutFactory.fillDefaults().numColumns(4).margins(0, 0).create());

        statusLabel = addLabelOneColumn(row2, "Status:");

        avgTimeLabel = addLabelTwoColumns(row2, "Avg time:");
        stddevLabel = addLabelTwoColumns(row2, "Std dev:");

        minTimeLabel = addLabelTwoColumns(row2, "Min time:");
        maxTimeLabel = addLabelTwoColumns(row2, "Max time:");

        return panel;
    }

    /*
     * Adds a label-value pair to a composite (spans two columns).
     */
    private Label addLabelTwoColumns(Composite c, String text)
    {
        final Label lb1 = new Label(c, SWT.LEAD);
        lb1.setText(text);
        lb1.setLayoutData(GridDataFactory.swtDefaults().create());

        final Label lb2 = new Label(c, SWT.LEAD);
        lb2.setText("");
        lb2.setLayoutData(GridDataFactory.swtDefaults().align(SWT.FILL, SWT.FILL).grab(true, false).create());

        return lb2;
    }

    /*
     * Adds a label-value pair to a composite (spans four columns).
     */
    private Label addLabelOneColumn(Composite c, String text)
    {
        final Label lb1 = new Label(c, SWT.LEAD);
        lb1.setText(text);
        lb1.setLayoutData(GridDataFactory.swtDefaults().create());

        final Label lb2 = new Label(c, SWT.LEAD);
        lb2.setText("");
        lb2.setLayoutData(GridDataFactory
            .swtDefaults().span(3, 1)
            .align(SWT.FILL, SWT.FILL).grab(true, false).create());

        return lb2;
    }
    
    /*
     * 
     */
    @Override
    public Control getControl()
    {
        return scroller;
    }

    /*
     * 
     */
    @Override
    public void setFocus()
    {
        this.startButton.setFocus();
    }
}
